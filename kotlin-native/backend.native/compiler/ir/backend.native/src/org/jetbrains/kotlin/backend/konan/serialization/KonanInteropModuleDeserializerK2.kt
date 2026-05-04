/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import kotlinx.metadata.klib.KlibModuleMetadata
import kotlinx.metadata.klib.annotations
import kotlinx.metadata.klib.compileTimeValue
import org.jetbrains.kotlin.backend.common.linkage.IrDeserializer.TopLevelSymbolKind
import org.jetbrains.kotlin.backend.common.serialization.CompatibilityMode
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializerKind
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.backend.konan.ir.BackendNativeSymbols
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBodyKind
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.metadata.KlibDeserializedContainerSource
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.metadata.isCInteropLibrary
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.serialization.deserialization.DeserializationConfiguration
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom
import org.jetbrains.kotlin.utils.mapToSetOrEmpty
import org.jetbrains.kotlin.utils.putToMultiMap
import java.lang.ref.SoftReference
import kotlin.metadata.*
import kotlin.metadata.ClassKind as KmClassKind
import kotlin.metadata.Modality as KmModality
import kotlin.metadata.Visibility as KmVisibility

internal class KonanInteropModuleDeserializerK2(
        private val deserializationConfiguration: DeserializationConfiguration,
        moduleDescriptor: ModuleDescriptor,
        override val klib: KotlinLibrary,
        private val isLibraryCached: Boolean,
        private val symbols: BackendNativeSymbols,
        private val linker: KonanIrLinker,
) : IrModuleDeserializer(moduleDescriptor, klib.versions.abiVersion ?: KotlinAbiVersion.CURRENT) {
    private val moduleHeaderProto: KlibMetadataProtoBuf.Header

    init {
        require(klib.isCInteropLibrary())
        moduleHeaderProto = parseModuleHeader(klib.metadata.moduleHeaderData)
    }

    override val kind get() = IrModuleDeserializerKind.DESERIALIZED
    override val moduleFragment: IrModuleFragment = IrModuleFragmentImpl(moduleDescriptor)

    private val symbolTable = linker.symbolTable
    private val builtIns = linker.builtIns
    private val signatureComputer = PublicIdSignatureComputer(KonanManglerIr, markAllAsCInterop = true)
    private val metadataReader = KlibMetadataReader(klib, moduleHeaderProto)

    private val deserializedClasses = mutableListOf<IrClass>()
    private val deserializedCallableDeclarations = mutableListOf<IrDeclarationWithName>()
    private val irPackagesByFqName = mutableMapOf<FqName, IrExternalPackageFragment>()
    private val typeDefinitionsFilesForPackage = mutableMapOf<IrExternalPackageFragment, IrFile>()
    private val annotatedElementsToDeserializeLater = mutableMapOf<IrMutableAnnotationContainer, List<KmAnnotation>>()

    private fun IdSignature.isInteropSignature() = IdSignature.Flags.IS_NATIVE_INTEROP_LIBRARY.test()

    override fun contains(idSig: IdSignature): Boolean {
        if (!idSig.isInteropSignature() || !idSig.hasTopLevel) {
            return false
        }

        val topLevelSig = idSig.topLevelSignature() as? IdSignature.CommonSignature ?: return false
        val packageFqName = FqName(topLevelSig.packageFqName)
        if (packageFqName !in metadataReader.definedPackageFqNames) {
            return false
        }

        val topLevelName = FqName(topLevelSig.declarationFqName.substringBefore('.'))
        for (kind in TopLevelSymbolKind.entries) {
            // C-interop Klibs do define type aliases, but all types in IR and metadata already provide their expanded representation,
            // and type aliases are not otherwise useful in IR, so there is no need to deserialize them.
            if (kind == TopLevelSymbolKind.TYPEALIAS_SYMBOL) continue

            val id = MetadataDeclarationId(kind, packageFqName, topLevelName)
            if (id in metadataReader.getDeclaredDeclarationIds()) {
                return true
            }
        }

        return false
    }

    override fun tryDeserializeIrSymbol(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol? {
        val symbol = symbolTable.referenceSymbolByKind(idSig, symbolKind) ?: return null
        if (symbol.isBound) return symbol

        var searchForSymbolKind = symbolKind
        var commonSig = idSig
        if (commonSig is IdSignature.AccessorSignature) {
            // When looking for a property's accessor, try to deserialize the property instead.
            // Doing so will, in turn, deserialize its accessors.
            commonSig = commonSig.propertySignature
            searchForSymbolKind = BinarySymbolData.SymbolKind.PROPERTY_SYMBOL
        }
        commonSig = commonSig as? IdSignature.CommonSignature ?: return null

        if ('.' in commonSig.declarationFqName) {
            // When looking for a class member, try to deserialize the (top-most) containing class instead.
            // Doing so will, in turn, deserialize everything declared inside that class (including nested classes, recursively).
            // If the sought declaration is indeed defined somewhere inside this class, it will be linked, although later.
            val topLevelClassSig = commonSig.topLevelSignature()
            tryDeserializeIrSymbol(topLevelClassSig, BinarySymbolData.SymbolKind.CLASS_SYMBOL)
        } else {
            val packageFqName = FqName(commonSig.packageFqName)
            val relativeDeclarationName = FqName(commonSig.declarationFqName)
            val declarationKind = when (searchForSymbolKind) {
                BinarySymbolData.SymbolKind.CLASS_SYMBOL -> TopLevelSymbolKind.CLASS_SYMBOL
                BinarySymbolData.SymbolKind.FUNCTION_SYMBOL -> TopLevelSymbolKind.FUNCTION_SYMBOL
                BinarySymbolData.SymbolKind.PROPERTY_SYMBOL -> TopLevelSymbolKind.PROPERTY_SYMBOL
                BinarySymbolData.SymbolKind.CONSTRUCTOR_SYMBOL,
                BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL -> error("This declaration cannot be top-level: $searchForSymbolKind")
                else -> error("Symbol kind is unsupported by C-interop Klib: $searchForSymbolKind")
            }
            val id = MetadataDeclarationId(declarationKind, packageFqName, relativeDeclarationName)

            val kmDeclarations = metadataReader.retrieveDeclarationsById(id)
            if (kmDeclarations != null) {
                val irPackage = getOrCreateContainingPackage(id.packageFqName)
                for (kmDeclaration in kmDeclarations) {
                    val irDeclaration = when (kmDeclaration) {
                        is KmClass -> deserializeClass(kmDeclaration, irPackage)
                        is KmFunction -> deserializeFunction(kmDeclaration, irPackage)
                        is KmProperty -> deserializeProperty(kmDeclaration, irPackage)
                        else -> error(kmDeclaration)
                    }
                    irPackage.addChild(irDeclaration)
                }
            }
        }

        return symbol
    }

    override fun deserializedSymbolNotFound(idSig: IdSignature): Nothing = error("No C-Interop symbol found for $idSig")

    private fun SymbolTable.referenceSymbolByKind(idSig: IdSignature, symbolKind: BinarySymbolData.SymbolKind): IrSymbol? {
        return when (symbolKind) {
            BinarySymbolData.SymbolKind.CLASS_SYMBOL -> referenceClass(idSig)
            BinarySymbolData.SymbolKind.CONSTRUCTOR_SYMBOL -> referenceConstructor(idSig)
            BinarySymbolData.SymbolKind.FUNCTION_SYMBOL -> referenceSimpleFunction(idSig)
            BinarySymbolData.SymbolKind.PROPERTY_SYMBOL -> referenceProperty(idSig)
            BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL -> referenceEnumEntry(idSig)
            else -> null
        }
    }

    private fun findReferencedClassifier(classifier: KmClassifier, typeParametersInScope: Map<Int, IrTypeParameter> = emptyMap()): IrClassifierSymbol {
        return when (classifier) {
            is KmClassifier.TypeParameter -> typeParametersInScope[classifier.id]?.symbol
                    ?: error("No type parameter with id ${classifier.id} found in the current scope.")
            is KmClassifier.Class -> findReferencedClass(classifier.name)
            is KmClassifier.TypeAlias -> error("Unexpected type alias reference.\n" +
                    "All types in metadata are expected to be expanded, except in KmType.abbreviatedType," +
                    "however, it should be ignored, as IR does not use type abbreviations.")
        }
    }

    private fun findReferencedClass(className: ClassName): IrClassSymbol {
        require(!className.isLocalClassName()) { "Local/anonymous classes are not supported: $className" }
        val pkgFqName = FqName(className.substringBeforeLast('/').replace("/", "."))
        val classFqName = className.substringAfterLast('/')

        // A C-interop Klib may only reference classes from the Kotlin stdlib, itself, or other C-interop Klibs.
        // Also, creating C-interop Klibs with package names used in stdlib (kotlin and kotlinx.cinterop) is prohibited (KT-85765).
        // We use that to tell whether a referenced class comes from Kolin code (the stdlib) or from native code.
        // This information is expected by IdSignature.
        val isFromStdlib = pkgFqName.isSubpackageOf(FqName("kotlin")) || pkgFqName.isSubpackageOf(FqName("kotlinx.cinterop"))
        val cinteropFlag = IdSignature.Flags.IS_NATIVE_INTEROP_LIBRARY.encode(!isFromStdlib)
        val classSignature = IdSignature.CommonSignature(pkgFqName.asString(), classFqName, null, cinteropFlag, null)

        return linker.deserializeOrReturnUnboundIrSymbolIfPartialLinkageEnabled(classSignature, BinarySymbolData.SymbolKind.CLASS_SYMBOL,
                this@KonanInteropModuleDeserializerK2) as IrClassSymbol
    }

    fun deserializeAllCStructsAndEnums() {
        for (id in metadataReader.getDeclaredDeclarationIds()) {
            if (id.kind != TopLevelSymbolKind.CLASS_SYMBOL) continue

            // All C structs and enums are expected to be top-level classes.
            // Also, nested classes cannot be loaded here directly, as all class members should be loaded only when deserializing
            // their parent class.
            if (!id.relativeDeclarationName.isOneSegmentFQN()) continue

            val kmClass = (metadataReader.retrieveDeclarationsById(id)?.firstOrNull() as KmClass?) ?: continue
            if (kmClass.supertypes.any {
                        val fqName = (it.classifier as? KmClassifier.Class)?.name ?: return@any false
                        fqName == "kotlinx/cinterop/CStructVar" || fqName == "kotlinx/cinterop/CEnum"
                    }) {
                val irPackage = getOrCreateContainingPackage(id.packageFqName)
                val irClass = deserializeClass(kmClass, irPackage)
                irPackage.addChild(irClass)
            }
        }
    }

    override fun postProcess() {
        super.postProcess()

        for ((element, annotations) in annotatedElementsToDeserializeLater) {
            element.annotations = annotations.map { deserializeAnnotation(it) }
        }
        annotatedElementsToDeserializeLater.clear()

        // Unless it's a class, we cannot compute the actual signature for deserialized declarations right away,
        // and so cannot link the declarations with a particular signature, too.
        // So first, we only deserialize the "promising" declarations (by FQ name),
        // but postpone computing the signature and the actual linkage to here.
        for (declaration in deserializedCallableDeclarations) {
            computeSignatureAndMaybeBind(declaration)
        }
        deserializedCallableDeclarations.clear()

        // If the C-interop Klib is already cached, the cache should contain all the implementation.
        if (!isLibraryCached) {
            val implGen = IrImplementationGeneratorForCStructsAndEnumsK2(builtIns, symbols)
            for (clazz in deserializedClasses) {
                generateImplIfCStructOrEnum(implGen, clazz)
            }
        }
        deserializedClasses.clear()
    }

    private fun computeSignatureAndMaybeBind(declaration: IrDeclarationWithName) {
        val signature = signatureComputer.computeSignature(declaration)
        when (declaration) {
            is IrSimpleFunction -> {
                val newSymbol = symbolTable.referenceSimpleFunction(signature)
                newSymbol.bind(declaration)
                (declaration as IrFunctionImpl).symbol = newSymbol
                symbolTable.declareSimpleFunction(signature, { newSymbol }, { declaration })
            }
            is IrConstructor -> {
                val newSymbol = symbolTable.referenceConstructor(signature)
                newSymbol.bind(declaration)
                (declaration as IrConstructorImpl).symbol = newSymbol
                symbolTable.declareConstructor(signature, { newSymbol }, { declaration })
            }
            is IrProperty -> {
                val newSymbol = symbolTable.referenceProperty(signature)
                newSymbol.bind(declaration)
                (declaration as IrPropertyImpl).symbol = newSymbol
                symbolTable.declareProperty(signature, { newSymbol }, { declaration })

                declaration.getter?.correspondingPropertySymbol = newSymbol
                declaration.setter?.correspondingPropertySymbol = newSymbol
            }
        }
    }

    private fun generateImplIfCStructOrEnum(cClassesImplGenerator: IrImplementationGeneratorForCStructsAndEnumsK2, clazz: IrClass) {
        var moveToIrFile = false
        if (clazz.inheritsFromCStruct()) {
            cClassesImplGenerator.generateImplementationForCStruct(clazz)
            moveToIrFile = true
        }
        if (clazz.inheritsFromCEnum()) {
            cClassesImplGenerator.generateImplementationForCEnum(clazz)
            moveToIrFile = true
        }

        if (moveToIrFile) {
            // Most declarations from C-interop Klib are just stubs, and are not meant to participate in lowering, so they are
            // put inside IrExternalPackageFragment. But C structs and enums should be lowered (unless already cached),
            // so they need to be "promoted" to IrFile, as only IrFiles are being lowered.
            val parent = clazz.parent
            if (parent is IrExternalPackageFragment) {
                val irFile = typeDefinitionsFilesForPackage.computeIfAbsent(parent) {
                    val fileEntry = NaiveSourceBasedFileEntryImpl(NativeStandardInteropNames.cTypeDefinitionsFileName)

                    @OptIn(ObsoleteDescriptorBasedAPI::class)
                    val irFile = IrFileImpl(fileEntry, IrFileSymbolImpl(parent.symbol.descriptor), parent.packageFqName, moduleFragment)
                    moduleFragment.files += irFile
                    irFile
                }

                parent.declarations.remove(clazz)
                irFile.declarations.add(clazz)
                clazz.parent = irFile
            }
        }
    }


    private fun getOrCreateContainingPackage(packageFqName: FqName): IrExternalPackageFragment {
        return irPackagesByFqName.computeIfAbsent(packageFqName) {
            val containerSource = KlibDeserializedContainerSource(klib, moduleHeaderProto, deserializationConfiguration, packageFqName, null)
            val descriptor = CInteropDeserializedPackageDescriptorK2(moduleDescriptor, packageFqName, containerSource)
            IrExternalPackageFragmentImpl(IrExternalPackageFragmentSymbolImpl(descriptor), packageFqName)
        }
    }

    private fun deserializeClass(kmClass: KmClass, parent: IrDeclarationParent): IrClass {
        require(kmClass.typeParameters.isEmpty()) { "C-interop classes are not expected to have type parameters." }
        require(!kmClass.name.isLocalClassName()) { "Local/anonymous classes are not supported: ${kmClass.name}" }

        val packageFqName = kmClass.name.substringBeforeLast('/').replace("/", ".")
        val classFqName = kmClass.name.substringAfterLast('/')
        val classSimpleName = classFqName.substringAfterLast('.')
        val signatureMask = IdSignature.Flags.IS_NATIVE_INTEROP_LIBRARY.encode(true)
        val signature = IdSignature.CommonSignature(packageFqName, classFqName, null, signatureMask, null)

        val clazz = symbolTable.declareClass(signature, { IrClassSymbolImpl(signature = signature) }) { symbol ->
            IrFactoryImpl.createClass(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    symbol = symbol,
                    origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    name = Name.identifier(classSimpleName),
                    visibility = kmClass.visibility.toDescriptorVisibility(),
                    modality = kmClass.modality.toDescriptorModality(),
                    kind = kmClass.kind.toDescriptorClassKind(),
                    isCompanion = kmClass.kind == KmClassKind.COMPANION_OBJECT,
                    isInner = kmClass.isInner,
                    isExpect = kmClass.isExpect,
                    isExternal = kmClass.isExternal,
                    isValue = kmClass.isValue,
                    isData = kmClass.isData,
                    isFun = kmClass.isFunInterface,
                    hasEnumEntries = kmClass.hasEnumEntries,
            )
        }

        check(!(kmClass.isExternal && (parent as? IrClass)?.isExternal == false)) { "Inconsistent isExternal status of classes, need to account for that when creating" }

        scheduleDeserializingAnnotations(clazz, kmClass.annotations)
        clazz.superTypes = if (kmClass.supertypes.isNotEmpty()) {
            kmClass.supertypes.map { it.toIrType() }
        } else {
            listOf(builtIns.anyType)
        }
        clazz.createThisReceiverParameter()
        clazz.parent = parent

        for (kmConstructor in kmClass.constructors) {
            clazz.declarations += deserializeConstructor(kmConstructor, clazz)
        }
        for (kmProperty in kmClass.properties) {
            clazz.declarations += deserializeProperty(kmProperty, clazz)
        }
        for (kmFunction in kmClass.functions) {
            clazz.declarations += deserializeFunction(kmFunction, clazz)
        }
        for (enumEntry in kmClass.kmEnumEntries) {
            clazz.declarations += deserializeEnumEntry(enumEntry, clazz, signature)
        }
        for (nestedClassName in kmClass.nestedClasses) {
            val nestedClassFqName = FqName(classFqName).child(Name.identifier(nestedClassName))
            val nestedClassId = MetadataDeclarationId(TopLevelSymbolKind.CLASS_SYMBOL, FqName(packageFqName), nestedClassFqName)
            val nestedKmClass = metadataReader.retrieveDeclarationsById(nestedClassId)?.first() as KmClass? ?: continue
            clazz.declarations += deserializeClass(nestedKmClass, clazz)
        }

        if (clazz.inheritsFromCEnum()) {
            val members = generateSpecialEnumMembers(clazz)
            clazz.declarations += members
            for (member in members) {
                member.patchDeclarationParents(clazz)

                deserializedCallableDeclarations += member
                deserializedCallableDeclarations += listOfNotNull((member as? IrProperty)?.getter, (member as? IrProperty)?.setter)
            }
        }

        linker.fakeOverrideBuilder.enqueueClass(clazz, signature, CompatibilityMode.CURRENT)
        deserializedClasses += clazz
        return clazz
    }

    private fun deserializeEnumEntry(kmEnumEntry: KmEnumEntry, parent: IrClass, parentSignature: IdSignature.CommonSignature): IrEnumEntry {
        val signature = IdSignature.CommonSignature(
                parentSignature.packageFqName,
                parentSignature.declarationFqName + "." + kmEnumEntry.name,
                null, parentSignature.mask, null
        )
        val enumEntry = symbolTable.declareEnumEntry(signature, { IrEnumEntrySymbolImpl(signature = signature) }) { symbol ->
            IrFactoryImpl.createEnumEntry(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    name = Name.identifier(kmEnumEntry.name),
                    symbol = symbol,
            )
        }

        scheduleDeserializingAnnotations(enumEntry, kmEnumEntry.annotations)

        enumEntry.parent = parent
        return enumEntry
    }

    private fun generateSpecialEnumMembers(enumClass: IrClass): List<IrDeclarationWithName> = buildList {
        this += IrFactoryImpl.buildFun {
            name = StandardNames.ENUM_VALUES
            origin = IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER
            returnType = builtIns.arrayClass.typeWith(enumClass.defaultType)
        }.apply {
            body = IrSyntheticBodyImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrSyntheticBodyKind.ENUM_VALUES)
        }

        this += IrFactoryImpl.buildFun {
            name = StandardNames.ENUM_VALUE_OF
            origin = IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER
            returnType = enumClass.defaultType
        }.apply {
            addValueParameter {
                name = Name.identifier("value")
                type = builtIns.stringType
            }
            body = IrSyntheticBodyImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrSyntheticBodyKind.ENUM_VALUEOF)
        }

        this += IrFactoryImpl.buildProperty {
            name = StandardNames.ENUM_ENTRIES
            origin = IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER
        }.also { property ->
            property.getter = IrFactoryImpl.buildFun {
                name = Name.special("<get-${StandardNames.ENUM_ENTRIES}>")
                origin = IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER
                returnType = symbols.enumEntriesInterface.typeWith(enumClass.defaultType)
            }.apply {
                correspondingPropertySymbol = property.symbol
                body = IrSyntheticBodyImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrSyntheticBodyKind.ENUM_ENTRIES)
            }
        }
    }

    private fun deserializeFunction(kmFunction: KmFunction, parent: IrDeclarationParent): IrSimpleFunction {
        val typeParametersById = deserializeTypeParameters(kmFunction.typeParameters)
        val function = IrFactoryImpl.createSimpleFunction(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                name = Name.identifier(kmFunction.name),
                symbol = IrSimpleFunctionSymbolImpl(),
                visibility = kmFunction.visibility.toDescriptorVisibility(),
                modality = kmFunction.modality.toDescriptorModality(),
                returnType = kmFunction.returnType.toIrType(typeParametersById),
                isExpect = kmFunction.isExpect,
                isInfix = kmFunction.isInfix,
                isExternal = kmFunction.isExternal,
                isInline = kmFunction.isInline,
                isTailrec = kmFunction.isTailrec,
                isSuspend = kmFunction.isSuspend,
                isOperator = kmFunction.isOperator,
                containerSource = null,
        )
        function.parameters = buildList {
            if (parent is IrClass) {
                addIfNotNull(parent.thisReceiver?.copyTo(function))
            }
            kmFunction.receiverParameterType?.let {
                add(createExtensionReceiverParameter(it.toIrType(typeParametersById),
                        kmFunction.extensionReceiverParameterAnnotations, function))
            }
            kmFunction.valueParameters.mapTo(this) { deserializeRegularParameter(it, function, typeParametersById) }

            @OptIn(ExperimentalContextParameters::class)
            require(kmFunction.contextParameters.isEmpty()) { "Context parameters are not expected" }
        }
        function.parameters.forEach { it.parent = function }

        function.typeParameters = typeParametersById.values.sortedBy { it.index }
        function.typeParameters.forEach { it.parent = function }

        scheduleDeserializingAnnotations(function, kmFunction.annotations)

        function.parent = parent
        deserializedCallableDeclarations += function
        return function
    }

    private fun deserializeConstructor(kmConstructor: KmConstructor, parent: IrClass): IrConstructor {
        val constructor = IrFactoryImpl.createConstructor(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                name = SpecialNames.INIT,
                symbol = IrConstructorSymbolImpl(),
                visibility = kmConstructor.visibility.toDescriptorVisibility(),
                returnType = parent.defaultType,
                isExpect = false,
                isExternal = false,
                isInline = false,
                isPrimary = !kmConstructor.isSecondary,
                containerSource = null,
        )
        constructor.parameters = kmConstructor.valueParameters.map { deserializeRegularParameter(it, constructor, emptyMap()) }
        constructor.parameters.forEach { it.parent = constructor }

        scheduleDeserializingAnnotations(constructor, kmConstructor.annotations)

        constructor.parent = parent
        deserializedCallableDeclarations += constructor
        return constructor
    }

    private fun deserializeProperty(kmProperty: KmProperty, parent: IrDeclarationParent): IrProperty {
        val property = IrFactoryImpl.createProperty(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                name = Name.identifier(kmProperty.name),
                symbol = IrPropertySymbolImpl(),
                visibility = kmProperty.visibility.toDescriptorVisibility(),
                modality = kmProperty.modality.toDescriptorModality(),
                isExpect = kmProperty.isExpect,
                isExternal = kmProperty.isExternal,
                isVar = kmProperty.isVar,
                isConst = kmProperty.isConst,
                isLateinit = kmProperty.isLateinit,
                isDelegated = kmProperty.isDelegated,
                containerSource = null,
        )
        property.getter = deserializeAccessor(kmProperty.getter, false, property, kmProperty, parent)
        property.getter?.parent = parent
        property.setter = kmProperty.setter?.let { deserializeAccessor(it, true, property, kmProperty, parent) }
        property.setter?.parent = parent

        kmProperty.compileTimeValue?.let { kmValue ->
            property.getter?.let { getter ->
                val irValue = deserializeAnnotationArgument(kmValue, getter.returnType)
                getter.body = IrFactoryImpl.createExpressionBody(irValue)
            }
        }

        scheduleDeserializingAnnotations(property, kmProperty.annotations)
        require(kmProperty.typeParameters.isEmpty()) { TODO("Function type parameters") }

        property.parent = parent
        deserializedCallableDeclarations += property
        return property
    }

    private fun deserializeAccessor(kmAccessor: KmPropertyAccessorAttributes, isSetter: Boolean, irProperty: IrProperty, kmProperty: KmProperty, parent: IrDeclarationParent): IrSimpleFunction {
        val propertyType = kmProperty.returnType.toIrType()
        val accessor = IrFactoryImpl.createSimpleFunction(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                name = if (isSetter) Name.special("<set-${irProperty.name}>") else Name.special("<get-${irProperty.name}>"),
                symbol = IrSimpleFunctionSymbolImpl(),
                visibility = kmAccessor.visibility.toDescriptorVisibility(),
                modality = kmAccessor.modality.toDescriptorModality(),
                returnType = if (isSetter) builtIns.unitType else propertyType,
                isExpect = kmProperty.isExpect,
                isInfix = false,
                isExternal = kmAccessor.isExternal,
                isInline = kmAccessor.isInline,
                isTailrec = false,
                isSuspend = false,
                isOperator = false,
                containerSource = null,
        )
        accessor.correspondingPropertySymbol = irProperty.symbol
        accessor.parameters = buildList {
            if (parent is IrClass) {
                addIfNotNull(parent.thisReceiver?.copyTo(accessor))
            }
            kmProperty.receiverParameterType?.let {
                add(createExtensionReceiverParameter(it.toIrType(),
                        kmProperty.extensionReceiverParameterAnnotations, accessor))
            }
            if (isSetter) {
                add(
                        IrFactoryImpl.createValueParameter(
                                startOffset = UNDEFINED_OFFSET,
                                endOffset = UNDEFINED_OFFSET,
                                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                                symbol = IrValueParameterSymbolImpl(),
                                name = Name.identifier("value"),
                                kind = IrParameterKind.Regular,
                                type = propertyType,
                                varargElementType = null,
                                isAssignable = false,
                                isCrossinline = false,
                                isNoinline = false,
                                isHidden = false,
                        )
                )
            }
        }
        accessor.parameters.forEach { it.parent = accessor }

        scheduleDeserializingAnnotations(accessor, kmAccessor.annotations)
        require(kmProperty.typeParameters.isEmpty()) { TODO("Function type parameters") }

        accessor.parent = parent
        deserializedCallableDeclarations += accessor
        return accessor
    }

    private fun deserializeRegularParameter(kmParameter: KmValueParameter, parent: IrFunction, typeParametersInScope: Map<Int, IrTypeParameter>): IrValueParameter {
        val parameter = IrFactoryImpl.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                symbol = IrValueParameterSymbolImpl(),
                name = Name.identifier(kmParameter.name),
                kind = IrParameterKind.Regular,
                type = kmParameter.type.toIrType(typeParametersInScope),
                varargElementType = kmParameter.varargElementType?.toIrType(typeParametersInScope),
                isAssignable = false,
                isCrossinline = kmParameter.isCrossinline,
                isNoinline = kmParameter.isNoinline,
                isHidden = false,
        )
        if (kmParameter.declaresDefaultValue) {
            parameter.defaultValue = parameter.createStubDefaultValue()
        }
        scheduleDeserializingAnnotations(parameter, kmParameter.annotations)

        parameter.parent = parent
        return parameter
    }

    private fun createExtensionReceiverParameter(type: IrType, kmAnnotations: List<KmAnnotation>, parent: IrFunction): IrValueParameter {
        val parameter = IrFactoryImpl.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                name = SpecialNames.RECEIVER,
                kind = IrParameterKind.ExtensionReceiver,
                type = type,
                symbol = IrValueParameterSymbolImpl(),
                isAssignable = false,
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false,
        )
        scheduleDeserializingAnnotations(parameter, kmAnnotations)

        parameter.parent = parent
        return parameter
    }

    private fun deserializeTypeParameters(kmParameters: List<KmTypeParameter>): Map<Int, IrTypeParameter> {
        val kmToIrParam = kmParameters.withIndex().associate { (index, kmParameter) ->
            kmParameter to IrFactoryImpl.createTypeParameter(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    symbol = IrTypeParameterSymbolImpl(),
                    name = Name.identifier(kmParameter.name),
                    isReified = kmParameter.isReified,
                    variance = kmParameter.variance.toIrVariance(),
                    index = index,
            )
        }

        val typeParamsById = kmToIrParam.mapKeys { it.key.id }
        for ((kmParameter, irParameter) in kmToIrParam) {
            irParameter.superTypes = kmParameter.upperBounds.map { it.toIrType(typeParamsById) }
        }

        return typeParamsById
    }

    private fun KmVisibility.toDescriptorVisibility(): DescriptorVisibility = when (this) {
        KmVisibility.PUBLIC -> DescriptorVisibilities.PUBLIC
        KmVisibility.INTERNAL -> DescriptorVisibilities.INTERNAL
        KmVisibility.PROTECTED -> DescriptorVisibilities.PROTECTED
        KmVisibility.PRIVATE -> DescriptorVisibilities.PRIVATE
        KmVisibility.PRIVATE_TO_THIS -> DescriptorVisibilities.PRIVATE_TO_THIS
        KmVisibility.LOCAL -> DescriptorVisibilities.LOCAL
    }

    private fun KmModality.toDescriptorModality(): Modality = when (this) {
        KmModality.FINAL -> Modality.FINAL
        KmModality.OPEN -> Modality.OPEN
        KmModality.ABSTRACT -> Modality.ABSTRACT
        KmModality.SEALED -> Modality.SEALED
    }

    private fun KmClassKind.toDescriptorClassKind(): ClassKind = when (this) {
        KmClassKind.CLASS -> ClassKind.CLASS
        KmClassKind.INTERFACE -> ClassKind.INTERFACE
        KmClassKind.ANNOTATION_CLASS -> ClassKind.ANNOTATION_CLASS
        KmClassKind.ENUM_CLASS -> ClassKind.ENUM_CLASS
        KmClassKind.ENUM_ENTRY -> ClassKind.ENUM_ENTRY
        KmClassKind.OBJECT -> ClassKind.OBJECT
        KmClassKind.COMPANION_OBJECT -> ClassKind.OBJECT
    }


    private fun scheduleDeserializingAnnotations(element: IrMutableAnnotationContainer, annotations: List<KmAnnotation>) {
        if (annotations.isNotEmpty()) {
            annotatedElementsToDeserializeLater[element] = annotations
        }
    }

    private fun deserializeAnnotation(kmAnnotation: KmAnnotation): IrAnnotation {
        val annotationClassSymbol = findReferencedClass(kmAnnotation.className)
        val annotationClass = linker.getDeclaration(annotationClassSymbol) as? IrClass
        val constructor = annotationClass?.constructors?.singleOrNull()
        if (constructor == null) {
            // A proper annotation cannot be created, so make some stub to report it later.
            return IrAnnotationImplRaw(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    type = linker.builtIns.anyType,
                    // An approximate signature of the missing annotation's constructor, so that PL prints something useful
                    // in case either a suitable constructor or the annotation class itself is not found.
                    symbol = IrConstructorSymbolImpl(signature = annotationClassSymbol.signature),
                    constructorTypeArgumentsCount = 0,
                    origin = null,
                    source = SourceElement.NO_SOURCE,
            )
        }

        val irAnnotation = IrAnnotationImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                type = annotationClass.defaultType,
                symbol = constructor.symbol,
                typeArgumentsCount = constructor.allTypeParameters.size,
                constructorTypeArgumentsCount = constructor.typeParameters.size,
        )
        val irArguments = List(constructor.parameters.size) { index ->
            val parameter = constructor.parameters[index]
            val name = parameter.name.asString()
            kmAnnotation.arguments[name]?.let { deserializeAnnotationArgument(it, parameter.type) }
        }
        irAnnotation.arguments.assignFrom(irArguments)
        return irAnnotation
    }

    private fun deserializeAnnotationArgument(kmArgument: KmAnnotationArgument, expectedType: IrType): IrExpression {
        return when (kmArgument) {
            is KmAnnotationArgument.ByteValue -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.byteType, kmArgument.value)
            is KmAnnotationArgument.ShortValue -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.shortType, kmArgument.value)
            is KmAnnotationArgument.IntValue -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.intType, kmArgument.value)
            is KmAnnotationArgument.LongValue -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.longType, kmArgument.value)
            is KmAnnotationArgument.UByteValue -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.ubyteType, kmArgument.value.toByte())
            is KmAnnotationArgument.UShortValue -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.ushortType, kmArgument.value.toShort())
            is KmAnnotationArgument.UIntValue -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.uintType, kmArgument.value.toInt())
            is KmAnnotationArgument.ULongValue -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.ulongType, kmArgument.value.toLong())
            is KmAnnotationArgument.FloatValue -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.floatType, kmArgument.value)
            is KmAnnotationArgument.DoubleValue -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.doubleType, kmArgument.value)
            is KmAnnotationArgument.CharValue -> IrConstImpl.char(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.charType, kmArgument.value)
            is KmAnnotationArgument.BooleanValue -> IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.booleanType, kmArgument.value)
            is KmAnnotationArgument.StringValue -> IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.stringType, kmArgument.value)
            is KmAnnotationArgument.AnnotationValue -> deserializeAnnotation(kmArgument.annotation)
            is KmAnnotationArgument.KClassValue -> {
                val classSymbol = findReferencedClass(kmArgument.className)
                IrClassReferenceImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.kClassClass.starProjectedType, classSymbol, classSymbol.defaultTypeWithoutArguments)
            }
            is KmAnnotationArgument.ArrayKClassValue -> TODO("Unexpected annotation argument kind used inside C-interop Klib: Array class reference")
            is KmAnnotationArgument.EnumValue -> {
                val pkgFqName = kmArgument.enumClassName.substringBeforeLast('/').replace("/", ".")
                val enumEntryFqName = kmArgument.enumClassName.substringAfterLast('/') + "." + kmArgument.enumEntryName
                val enumEntrySig = IdSignature.CommonSignature(pkgFqName, enumEntryFqName, null, 0, null)
                val enumEntrySymbol = linker.deserializeOrReturnUnboundIrSymbolIfPartialLinkageEnabled(
                        enumEntrySig, BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL, this) as IrEnumEntrySymbol
                val enumClassSymbol = findReferencedClass(kmArgument.enumClassName)
                val irType = enumClassSymbol.defaultTypeWithoutArguments
                IrGetEnumValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irType, enumEntrySymbol)
            }
            is KmAnnotationArgument.ArrayValue -> {
                val varargElementType = (expectedType as IrSimpleType).arguments.first().typeOrFail
                val elements = kmArgument.elements.map { deserializeAnnotationArgument(it, varargElementType) }
                IrVarargImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expectedType, varargElementType, elements)
            }
        }
    }


    private fun KmType.toIrType(typeParametersInScope: Map<Int, IrTypeParameter> = emptyMap()): IrType {
        require(flexibleTypeUpperBound == null) { "Flexible types are not supported in K/Native" }
        require(this.outerType == null) { TODO("outerType") }
        require(this.annotations.isEmpty()) { TODO("type annotations") }

        val classifier = findReferencedClassifier(classifier, typeParametersInScope)
        return IrSimpleTypeImpl(
                classifier = classifier,
                nullability = if (isNullable) SimpleTypeNullability.MARKED_NULLABLE else SimpleTypeNullability.DEFINITELY_NOT_NULL,
                arguments = this.arguments.map { it.toIrTypeArgument(typeParametersInScope) },
                annotations = emptyList(),
        )
    }

    private fun KmTypeProjection.toIrTypeArgument(typeParametersInScope: Map<Int, IrTypeParameter>): IrTypeArgument = when (this) {
        KmTypeProjection.STAR -> IrStarProjectionImpl
        else -> makeTypeProjection(type!!.toIrType(typeParametersInScope), variance!!.toIrVariance())
    }

    private fun KmVariance.toIrVariance(): Variance = when (this) {
        KmVariance.INVARIANT -> Variance.INVARIANT
        KmVariance.IN -> Variance.INVARIANT
        KmVariance.OUT -> Variance.OUT_VARIANCE
    }
}

private class KlibMetadataReader(
        private val klib: KotlinLibrary,
        moduleHeaderProto: KlibMetadataProtoBuf.Header,
) {
    val definedPackageFqNames: Set<FqName> = (moduleHeaderProto.packageFragmentNameList.toSet() - moduleHeaderProto.emptyPackageList)
            .mapToSetOrEmpty { FqName(it) }

    private var isMetadataLoaded = false
    private val packagePartNamesForTopLevelDeclarations = mutableMapOf<FqName, MutableSet<String>>()

    // A cache of all package-level metadata declarations defined in a Klib. Note that this also includes nested classes,
    // because in metadata, they are serialized at package level. (In other words, this map contains both `Map` and `Map.Entry`, but not
    // their methods.)
    // The value is of type Any, because there is no common type between e.g., KmClass and KmFunction.
    // It is wrapped in a List, because there may be multiple functions and properites with the same FQ name. Of course, for classes, it's
    // expected there exist only one per FQ name.
    // It is wrapped in a SoftReference, because keeping all metadata from all C-Interop libraries may have a significant pressure on memory.
    // When a given metadata declaration is converted into an IR declaration, the entry is replaced with a `null` value, as the metadata
    // representation is no longer needed. The entry is not removed completely, so the map still lists all the declarations from the Klib.
    val allMetadataDeclarations: MutableMap<MetadataDeclarationId, SoftReference<List<Any>>?> = hashMapOf()

    private fun ensureMetadataLoaded() {
        if (!isMetadataLoaded) {
            loadMetadata()
        }
    }

    private fun loadMetadata() {
        val metadataComponent = klib.metadata

        // Read all the declarations defined in the Klib. This not only pre-populates the declaration cache but also records
        // which declaration is defined in which package fragment, which is used to re-read it later if the cache gets evicted.
        for (packageFqName in definedPackageFqNames) {
            val packageFragmentNames = metadataComponent.getPackageFragmentNames(packageFqName.asString())
            loadAndCachePackageFragments(packageFqName, packageFragmentNames)
        }
        isMetadataLoaded = true
    }

    private fun loadAndCachePackageFragments(packageFqName: FqName, packageFragmentNames: Collection<String>): Map<MetadataDeclarationId, List<Any>> {
        val deserializedDeclarations = mutableMapOf<MetadataDeclarationId, MutableList<Any>>()
        for (packageFragmentName in packageFragmentNames) {
            val packageFragmentBytes = klib.metadata.getPackageFragment(packageFqName.asString(), packageFragmentName)
            val packageFragment = KlibModuleMetadata.readPackageFragment(packageFragmentBytes)
            val topLevelNamesInPackageFragment = mutableSetOf<String>()

            for (clazz in packageFragment.classes) {
                val classFqName = FqName(clazz.name.substringAfterLast('/'))
                val id = MetadataDeclarationId(TopLevelSymbolKind.CLASS_SYMBOL, packageFqName, classFqName)
                deserializedDeclarations.putToMultiMap(id, clazz)
                topLevelNamesInPackageFragment += classFqName.shortName().asString()
            }

            val pkg = packageFragment.pkg
            if (pkg != null) {
                for (function in pkg.functions) {
                    val id = MetadataDeclarationId(TopLevelSymbolKind.FUNCTION_SYMBOL, packageFqName, FqName(function.name))
                    deserializedDeclarations.putToMultiMap(id, function)
                    topLevelNamesInPackageFragment += function.name
                }
                for (property in pkg.properties) {
                    val id = MetadataDeclarationId(TopLevelSymbolKind.PROPERTY_SYMBOL, packageFqName, FqName(property.name))
                    deserializedDeclarations.putToMultiMap(id, property)
                    topLevelNamesInPackageFragment += property.name
                }
            }

            for (name in topLevelNamesInPackageFragment) {
                val topLevelFqName = packageFqName.child(Name.identifier(name))
                packagePartNamesForTopLevelDeclarations.computeIfAbsent(topLevelFqName) { mutableSetOf() }.add(packageFragmentName)
            }
        }

        for ((id, declarations) in deserializedDeclarations) {
            if (id !in allMetadataDeclarations || allMetadataDeclarations[id]?.get() == null) {
                allMetadataDeclarations[id] = SoftReference(declarations)
            }
        }

        return deserializedDeclarations
    }

    fun getDeclaredDeclarationIds(): Set<MetadataDeclarationId> {
        ensureMetadataLoaded()
        return allMetadataDeclarations.keys
    }

    // Note: calling this function a second time for the same id will return `null`.
    fun retrieveDeclarationsById(id: MetadataDeclarationId): List<Any>? {
        ensureMetadataLoaded()
        val ref = allMetadataDeclarations.replace(id, null) ?: return null
        ref.get()?.let { return it }
        val topLevelFqName = id.packageFqName.child(id.relativeDeclarationName.shortName())
        val packageFragmentNames = packagePartNamesForTopLevelDeclarations[topLevelFqName] ?: return null
        val allDeclarations = loadAndCachePackageFragments(id.packageFqName, packageFragmentNames)
        return allDeclarations[id]
    }
}

private data class MetadataDeclarationId(
        val kind: TopLevelSymbolKind,
        val packageFqName: FqName,
        val relativeDeclarationName: FqName,
)

class CInteropDeserializedPackageDescriptorK2(
        module: ModuleDescriptor, fqName: FqName,
        private val containerSource: KlibDeserializedContainerSource
) : PackageFragmentDescriptorImpl(module, fqName) {
    override fun getMemberScope(): MemberScope = error("No K1 for you")
    override fun getSource(): SourceElement = containerSource
}