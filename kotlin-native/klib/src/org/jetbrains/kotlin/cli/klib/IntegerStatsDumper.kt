/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.klib

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.DeclarationDescriptorVisitorEmptyBodies
import org.jetbrains.kotlin.kotlinp.Printer
import org.jetbrains.kotlin.kotlinp.klib.*
import org.jetbrains.kotlin.kotlinp.klib.TypeArgumentId.VarianceId
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.contains
import kotlin.collections.mutableMapOf

internal class DumpIntegerStats(output: KlibToolOutput, args: KlibToolArguments) : KlibToolCommand(output, args) {
    override fun execute() {
        val library = loadKlib(args.libraryPath, output) ?: return
        IntegerStatsDumper(output).processLibrary(library)
    }
}

internal class IntegerStatsDumper(
        private val output: KlibToolOutput,
) {
    private val printer = Printer(output)

    fun processLibrary(library: KotlinLibrary) {
        val moduleDescriptor = ModuleDescriptorLoader(output).load(library) ?: return

        val typealiasMappingCollector = TypealiasMappingCollector().also { signatureCollector ->
            moduleDescriptor.accept(signatureCollector, Unit)

            for (integerType in TypealiasMappingCollector.INTEGER_TYPES) {
                printer.appendLine("Typealiases to $integerType:")
                val typeAliases = signatureCollector.integersToTypealiases.entries.find { it.key.toString() == integerType }?.value
                        ?: continue

                for ((index, alias) in typeAliases.withIndex()) {
                    val isLeafTypealias = alias.expandedType == alias.underlyingType
                    val expansionDescriptor = alias.underlyingType.constructor.declarationDescriptor ?: error("Typealias with bad expansion")

                    val aliasRepresentation = when {
                        isLeafTypealias -> "${alias.fqNameSafe}"
                        else -> "${expansionDescriptor.fqNameSafe} <- ${alias.fqNameSafe}"
                    }

                    printer.appendLine("${index + 1}. $integerType <- $aliasRepresentation")
                }
            }
        }

        TypealiasUsageCollector(typealiasMappingCollector.integersToTypealiases).let { signatureCollector ->
            moduleDescriptor.accept(signatureCollector, Unit)

            if (signatureCollector.typealiasUsages.isEmpty()) {
                printer.appendLine("Typealias usages are empty...")
            }

            for ((alias, usages) in signatureCollector.typealiasUsages) {
                printer.appendLine("Typealias ${alias.fqNameSafe} is used...")
//                printer.appendLine("- by ${usages.callablesUsingItInContextParameters.size} callables in their context parameters")
//                printer.appendLine("- by ${usages.callablesUsingItInReceiverType.size} callables in their receiver parameter")
                printer.appendLine("- by ${usages.callablesUsingItInValueParameter.size} functions in their value parameters")

                val properties = usages.callablesUsingItInReturnType.filterIsInstance<PropertyDescriptor>()
                printer.appendLine("- by ${usages.callablesUsingItInReturnType.size - properties.size} functions in their return type")
                printer.appendLine("- by ${properties.size} properties in their return type")

                printer.appendLine("- by ${usages.classesInTheirProperties.size} classes in their properties")
            }
        }
    }
}

private class TypealiasMappingCollector : DeclarationDescriptorVisitorEmptyBodies<Unit, Unit>() {
    companion object {
        val INTEGER_TYPES = listOf("Byte", "Short", "Int", "Long", "UByte", "UShort", "UInt", "ULong", "Char")
        val integerTypesHashSet = INTEGER_TYPES.toHashSet()
    }

    var integersToTypealiases = mutableMapOf<SimpleType, MutableList<TypeAliasDescriptor>>()

    override fun visitModuleDeclaration(module: ModuleDescriptor, data: Unit) {
        module.getPackageFragments().forEach { it.accept(this, data) }
    }

    override fun visitPackageFragmentDescriptor(fragment: PackageFragmentDescriptor, data: Unit) {
        fragment.getMemberScope().getContributedDescriptors().forEach { it.accept(this, data) }
    }

    override fun visitClassDescriptor(clazz: ClassDescriptor, data: Unit) {
        clazz.constructors.forEach { it.accept(this, data) }
        clazz.unsubstitutedMemberScope.getContributedDescriptors().forEach { it.accept(this, data) }
    }

    override fun visitPropertyDescriptor(property: PropertyDescriptor, data: Unit) {
        property.getter?.accept(this, data)
        property.setter?.accept(this, data)
    }

    override fun visitTypeAliasDescriptor(typeAlias: TypeAliasDescriptor, data: Unit) {
        if (typeAlias.expandedType.toString() in integerTypesHashSet) {
            integersToTypealiases.getOrPut(typeAlias.expandedType) { mutableListOf() }.add(typeAlias)
        }
    }
}

private class TypealiasUsageCollector(
        integersToTypealiases: Map<SimpleType, List<TypeAliasDescriptor>>,
) : DeclarationDescriptorVisitorEmptyBodies<Unit, Unit>() {
    private val allTypealiases = integersToTypealiases.values.flatten().toHashSet()
    private val allTypealiasIds = allTypealiases.associateBy { it.id() }.toMutableMap()

    val typealiasUsages = mutableMapOf<TypeAliasDescriptor, TypealiasUsageInfo>()

    data class TypealiasUsageInfo(
//            val callablesUsingItInContextParameters: MutableList<CallableDescriptor> = mutableListOf(),
//            val callablesUsingItInReceiverType: MutableList<CallableDescriptor> = mutableListOf(),
            val callablesUsingItInValueParameter: MutableList<CallableDescriptor> = mutableListOf(),
            val callablesUsingItInReturnType: MutableList<CallableDescriptor> = mutableListOf(),
            val classesInTheirProperties: MutableList<ClassDescriptor> = mutableListOf(),
    )

    override fun visitModuleDeclaration(module: ModuleDescriptor, data: Unit) {
        module.getPackageFragments().forEach { it.accept(this, data) }
    }

    override fun visitPackageFragmentDescriptor(fragment: PackageFragmentDescriptor, data: Unit) {
        fragment.getMemberScope().getContributedDescriptors().forEach { it.accept(this, data) }
    }

    override fun visitClassDescriptor(clazz: ClassDescriptor, data: Unit) {
        clazz.constructors.forEach { it.accept(this, data) }
        clazz.unsubstitutedMemberScope.getContributedDescriptors().forEach { it.accept(this, data) }
    }

    override fun visitConstructorDescriptor(constructor: ConstructorDescriptor, data: Unit) {
        visitCallableDescriptor(constructor)
    }

    override fun visitFunctionDescriptor(function: FunctionDescriptor, data: Unit) {
        visitCallableDescriptor(function)
    }

    override fun visitPropertyDescriptor(property: PropertyDescriptor, data: Unit) {
        visitCallableDescriptor(property)
//        property.getter?.let(::visitCallableDescriptor)
//        property.setter?.let(::visitCallableDescriptor)
    }

    private fun visitCallableDescriptor(callable: CallableDescriptor) {
//        callable.contextReceiverParameters.forEach {
//            getMentionedIntegerTypeAliases(it.type).forEach { alias ->
//                typealiasUsages.getOrPut(alias) { TypealiasUsageInfo() }.callablesUsingItInContextParameters.add(callable)
//            }
//        }
//
//        callable.extensionReceiverParameter?.type?.let(::getMentionedIntegerTypeAliases)?.forEach { alias ->
//            typealiasUsages.getOrPut(alias) { TypealiasUsageInfo() }.callablesUsingItInReceiverType.add(callable)
//        }

        callable.valueParameters.forEach {
            getMentionedIntegerTypeAliases(it.type).forEach { alias ->
                typealiasUsages.getOrPut(alias) { TypealiasUsageInfo() }.callablesUsingItInValueParameter.add(callable)
            }
        }

        val container = callable.containingDeclaration
        val usagesInReturnTypes = callable.returnType?.let(::getMentionedIntegerTypeAliases)

        usagesInReturnTypes?.forEach { alias ->
            typealiasUsages.getOrPut(alias) { TypealiasUsageInfo() }.callablesUsingItInReturnType.add(callable)
        }

        if (
                usagesInReturnTypes != null &&
                callable is PropertyDescriptor &&
                container is ClassDescriptor
        ) {
            for (alias in usagesInReturnTypes) {
                typealiasUsages.getOrPut(alias) { TypealiasUsageInfo() }.classesInTheirProperties.add(container)
            }
        }
    }

    fun getMentionedIntegerTypeAliases(type: KotlinType): List<TypeAliasDescriptor> {
        return buildList {
            type.getAbbreviation()?.contains {
                val id: ClassOrTypeAliasId = it.id().classifier as? ClassOrTypeAliasId ?: return@contains false
                allTypealiasIds[id]?.let { aliasId -> add(aliasId) }
                false
            }
        }
    }

    private fun ClassifierDescriptorWithTypeParameters.id() = classId?.asString()?.let(::ClassOrTypeAliasId)

    private fun DeclarationDescriptor.qualifiedName(): String {
        fun ClassifierDescriptorWithTypeParameters.classIdOrFail() = classId
                ?: error("Failed to compute class ID for ${this::class.java}, $this")

        return when (this) {
            is ClassifierDescriptorWithTypeParameters -> classIdOrFail()
            is CallableDescriptor -> when (val containingDeclaration = containingDeclaration) {
                is ClassifierDescriptorWithTypeParameters -> containingDeclaration.classIdOrFail().createNestedClassId(name)
                is PackageFragmentDescriptor -> ClassId(containingDeclaration.fqName, name)
                else -> containingDeclaration.unexpectedDeclarationType()
            }
            else -> unexpectedDeclarationType()
        }.asString()
    }

    private fun Variance.id() = when (this) {
        Variance.INVARIANT -> VarianceId.INVARIANT
        Variance.IN_VARIANCE -> VarianceId.IN
        Variance.OUT_VARIANCE -> VarianceId.OUT
    }

    private fun SimpleType.classifierId(): ClassifierId = when (val typeConstructorDescriptor = constructor.declarationDescriptor) {
        is TypeParameterDescriptor -> TypeParameterId(typeConstructorDescriptor.index)
        is ClassifierDescriptorWithTypeParameters -> ClassOrTypeAliasId(typeConstructorDescriptor.qualifiedName())
        else -> typeConstructorDescriptor.unexpectedDeclarationType()
    }

    private fun TypeProjection.id() = if (isStarProjection) TypeArgumentId.Star else TypeArgumentId.Regular(type.id(), projectionKind.id())

    private fun KotlinType.id(): TypeId {
        val simpleType = asSimpleType()
        return TypeId(simpleType.classifierId(), simpleType.arguments.map { it.id() })
    }

    private fun DeclarationDescriptor?.unexpectedDeclarationType(): Nothing =
            error(if (this == null) "Declaration descriptor is null" else "Unexpected declaration type: ${this::class.java}, $this")
}
