package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.linkage.issues.checkNoUnboundSymbols
import org.jetbrains.kotlin.backend.common.linkage.partial.createPartialLinkageSupportForLinker
import org.jetbrains.kotlin.backend.common.linkage.partial.partialLinkageConfig
import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideChecker
import org.jetbrains.kotlin.backend.common.phaser.KotlinBackendIrHolder
import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.moduleDeserializer
import org.jetbrains.kotlin.backend.konan.driver.NativeBackendPhaseContext
import org.jetbrains.kotlin.backend.konan.ir.BackendNativeSymbols
import org.jetbrains.kotlin.backend.konan.ir.konanLibrary
import org.jetbrains.kotlin.backend.konan.serialization.*
import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.DescriptorMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.objcinterop.IrObjCOverridabilityCondition
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.ReferenceSymbolTable
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.konan.config.fakeOverrideValidator
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.isHeader
import org.jetbrains.kotlin.library.metadata.DeserializedKlibModuleOrigin
import org.jetbrains.kotlin.library.metadata.KlibModuleOrigin
import org.jetbrains.kotlin.library.metadata.impl.isForwardDeclarationModule
import org.jetbrains.kotlin.library.metadata.kotlinLibrary
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.psi2ir.descriptors.IrBuiltInsOverDescriptors
import org.jetbrains.kotlin.psi2ir.generators.TypeTranslatorImpl
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CommonCompilerDeserializationConfiguration
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.serialization.deserialization.DeserializationConfiguration
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.mapToSetOrEmpty

internal interface LinkKlibsContext : NativeBackendPhaseContext {
    val symbolTable: SymbolTable?

    val reflectionTypes: KonanReflectionTypes

    val builtIns: KonanBuiltIns

    val bindingContext: BindingContext

    val stdlibModule: ModuleDescriptor
        get() = this.builtIns.any.module
}

data class LinkKlibsInput(
        val moduleDescriptor: ModuleDescriptor,
)

internal class LinkKlibsOutput(
        val irModules: Map<String, IrModuleFragment>,
        val irModule: IrModuleFragment,
        val irBuiltIns: IrBuiltIns,
        val symbols: BackendNativeSymbols,
        val symbolTable: ReferenceSymbolTable,
        val irLinker: KonanIrLinker,
) : KotlinBackendIrHolder {

    override val kotlinIr: IrElement
        get() = irModule
}


@OptIn(ObsoleteDescriptorBasedAPI::class)
internal fun LinkKlibsContext.linkKlibs(
        input: LinkKlibsInput
): LinkKlibsOutput {
    val symbolTable = symbolTable!!
    val moduleDescriptor = input.moduleDescriptor
    // Translate AST to high level IR.
    val messageCollector = config.configuration.messageCollector

    val typeTranslator = TypeTranslatorImpl(symbolTable, config.configuration.languageVersionSettings, moduleDescriptor)
    val irBuiltIns = IrBuiltInsOverDescriptors(moduleDescriptor.builtIns, typeTranslator, symbolTable)

    val forwardDeclarationsModuleDescriptor = moduleDescriptor.allDependencyModules.firstOrNull { it.isForwardDeclarationModule }

    val libraryToCache = config.libraryToCache
    val libraryToCacheModule = libraryToCache?.klib?.let {
        moduleDescriptor.allDependencyModules.single { module -> module.konanLibrary == it }
    }

    val stdlibIsCached = stdlibModule.konanLibrary?.let { config.cachedLibraries.isLibraryCached(it) } == true
    val stdlibIsBeingCached = libraryToCacheModule == stdlibModule
    require(!(stdlibIsCached && stdlibIsBeingCached)) { "The cache for stdlib is already built" }

    val symbols = BackendNativeSymbols(
            this,
            irBuiltIns,
            this.config.configuration
    )
    val deserializationConfiguration = CommonCompilerDeserializationConfiguration(config.configuration.languageVersionSettings)

    val mainModule = IrModuleFragmentImpl(moduleDescriptor)
    val irDeserializer = run {
        val exportedDependencies = (moduleDescriptor.getExportedDependencies(config) + libraryToCacheModule?.let { listOf(it) }.orEmpty()).distinct()
        val cInteropModuleDeserializerFactory = KonanCInteropModuleDeserializerFactory(
                deserializationConfiguration = deserializationConfiguration,
                cachedLibraries = config.cachedLibraries,
                symbols = symbols,
        )

        // TODO Don't use file names in friend modules detection. Should be done in scope of KT-61096
        val canonicalFriendPaths = config.friendModuleFiles.mapToSetOrEmpty { it.canonicalPath }
        val friendModules = config.resolvedLibraries.getFullList()
                .filter { it.libraryFile.canonicalPath in canonicalFriendPaths }
                .map { it.uniqueName }

        val friendModulesMap = (
                listOf(moduleDescriptor.name.asStringStripSpecialMarkers()) +
                        config.includedLibraries.map { it.uniqueName }
                ).associateWith { friendModules }

        val irDiagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
                config.configuration.diagnosticsCollector,
                config.languageVersionSettings,
        )

        KonanIrLinker(
                currentModule = moduleDescriptor,
                messageCollector = messageCollector,
                builtIns = irBuiltIns,
                symbolTable = symbolTable,
                friendModules = friendModulesMap,
                forwardModuleDescriptor = forwardDeclarationsModuleDescriptor,
                cInteropModuleDeserializerFactory = cInteropModuleDeserializerFactory,
                exportedDependencies = exportedDependencies,
                partialLinkageSupport = createPartialLinkageSupportForLinker(
                        partialLinkageConfig = config.configuration.partialLinkageConfig,
                        builtIns = irBuiltIns,
                        diagnosticReporter = irDiagnosticReporter,
                ),
                libraryBeingCached = config.libraryToCache,
                externalOverridabilityConditions = listOf(IrObjCOverridabilityCondition)
        ).also { linker ->

            // context.config.librariesWithDependencies could change at each iteration.
            var dependenciesCount = 0
            while (true) {
                // context.config.librariesWithDependencies could change at each iteration.
                val libsWithDeps = config.librariesWithDependencies().toSet()
                val dependencies = moduleDescriptor.allDependencyModules.filter {
                    libsWithDeps.contains(it.konanLibrary)
                }

                fun sortDependencies(dependencies: List<ModuleDescriptor>): Collection<ModuleDescriptor> {
                    return DFS.topologicalOrder(dependencies) {
                        it.allDependencyModules
                    }.reversed()
                }

                for (dependency in sortDependencies(dependencies).filter { it != moduleDescriptor }) {
                    val kotlinLibrary = (dependency.getCapability(KlibModuleOrigin.CAPABILITY) as? DeserializedKlibModuleOrigin)?.library
                    val isFullyCachedLibrary = kotlinLibrary != null &&
                            config.cachedLibraries.isLibraryCached(kotlinLibrary) && kotlinLibrary != config.libraryToCache?.klib
                    if (isFullyCachedLibrary && kotlinLibrary.isHeader)
                        linker.deserializeHeadersWithInlineBodies(dependency, kotlinLibrary)
                    else if (isFullyCachedLibrary)
                        linker.deserializeOnlyHeaderModule(dependency, kotlinLibrary)
                    else
                        linker.deserializeIrModuleHeader(dependency, kotlinLibrary, dependency.name.asString())
                }
                if (dependencies.size == dependenciesCount) break
                dependenciesCount = dependencies.size
            }

            // When building a cache for a C-interop library, ensure all the C structs and enums defined there are loaded.
            // This is because there is a synthetic implementation generated for them (see IrImplementationGeneratorForCStructsAndEnumsK2),
            // which ends up being compiled into assembly code.
            // We want to build a static cache for all such code per-CInterop-Klib, in the similar fasction to the regular (IR) Klibs.
            if (libraryToCacheModule?.isFromCInteropLibrary() == true) {
                val cinteropModule = linker.deserializeIrModuleHeader(libraryToCacheModule, libraryToCacheModule.kotlinLibrary, libraryToCacheModule.name.asString())
                (cinteropModule.moduleDeserializer as? KonanInteropModuleDeserializerK2)?.deserializeAllCStructsAndEnums()
            }

            linker.init(mainModule)
            ExternalDependenciesGenerator(linker.symbolTable, listOf(linker)).generateUnboundSymbolsAsDependencies()
            linker.postProcess(inOrAfterLinkageStep = true)
        }
    }

    messageCollector.checkNoUnboundSymbols(symbolTable, "at the end of IR linkage process")

    val modules = irDeserializer.modules

    if (config.configuration.fakeOverrideValidator) {
        val fakeOverrideChecker = FakeOverrideChecker(KonanManglerIr, KonanManglerDesc)
        modules.values.forEach { fakeOverrideChecker.check(it) }
    }
    // IR linker deserializes files in the order they lie on the disk, which might be inconvenient,
    // so to make the pipeline more deterministic, the files are to be sorted.
    // This concerns in the first place global initializers order for the eager initialization strategy,
    // where the files are being initialized in order one by one.
    modules.values.forEach { module -> module.files.sortBy { it.fileEntry.name } }

    if (stdlibIsBeingCached) {
        val maxArity = 255 // See [BuiltInFictitiousFunctionClassFactory].
        (0..maxArity).forEach { arity ->
            irDeserializer.builtIns.functionN(arity)
            irDeserializer.builtIns.suspendFunctionN(arity)
            irDeserializer.builtIns.kFunctionN(arity)
            irDeserializer.builtIns.kSuspendFunctionN(arity)
        }
    }

    mainModule.files.forEach { it.metadata = DescriptorMetadataSource.File(listOf(mainModule.descriptor)) }
    modules.values.forEach { module ->
        module.files.forEach { it.metadata = DescriptorMetadataSource.File(listOf(module.descriptor)) }
    }

    return if (libraryToCache == null) {
        LinkKlibsOutput(modules, mainModule, irBuiltIns, symbols, symbolTable, irDeserializer)
    } else {
        val libraryName = libraryToCache.klib.location.path
        val libraryModule = modules[libraryName] ?: error("No module for the library being cached: $libraryName")
        LinkKlibsOutput(
                irModules = modules.filterKeys { it != libraryName },
                irModule = libraryModule,
                irBuiltIns = irBuiltIns,
                symbols = symbols,
                symbolTable = symbolTable,
                irLinker = irDeserializer
        )
    }
}

internal class KonanCInteropModuleDeserializerFactory(
        private val cachedLibraries: CachedLibraries,
        private val deserializationConfiguration: DeserializationConfiguration,
        private val symbols: BackendNativeSymbols,
) : CInteropModuleDeserializerFactory {
    override fun createIrModuleDeserializer(
            moduleDescriptor: ModuleDescriptor,
            klib: KotlinLibrary,
            linker: KonanIrLinker,
    ): IrModuleDeserializer = KonanInteropModuleDeserializerK2(
            deserializationConfiguration,
            moduleDescriptor,
            klib,
            cachedLibraries.isLibraryCached(klib),
            symbols,
            linker,
    )
}
