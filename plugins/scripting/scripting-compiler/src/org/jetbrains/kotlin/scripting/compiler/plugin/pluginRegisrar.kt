/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("DEPRECATION_ERROR")

package org.jetbrains.kotlin.scripting.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.registerExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.CollectAdditionalSourceFilesExtension
import org.jetbrains.kotlin.scripting.compiler.plugin.extensions.ReplLoweringExtension
import org.jetbrains.kotlin.scripting.compiler.plugin.extensions.ScriptLoweringExtension
import org.jetbrains.kotlin.scripting.compiler.plugin.fir.CollectAdditionalScriptSourcesExtension

// Scripting infrastructure still depends on project-based components, therefore we still need a separate registrar above - ScriptingCompilerConfigurationComponentRegistrar
// TODO: refactor components and migrate the plugin to the project-independent operation
class ScriptingK2CompilerPluginRegistrar : CompilerPluginRegistrar() {
    companion object {
        fun registerComponents(extensionStorage: ExtensionStorage, compilerConfiguration: CompilerConfiguration) = with(extensionStorage) {
            FirExtensionRegistrar.registerExtension(FirScriptingCompilerExtensionRegistrar(compilerConfiguration))
            FirExtensionRegistrar.registerExtension(FirScriptingSamWithReceiverExtensionRegistrar())

            with(compilerConfiguration.extensionsStorage!!) {
                IrGenerationExtension.registerExtension(ScriptLoweringExtension())
                IrGenerationExtension.registerExtension(ReplLoweringExtension())
            }
        }
    }

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        registerComponents(this, configuration)

        CollectAdditionalSourceFilesExtension.registerExtension(CollectAdditionalScriptSourcesExtension())
    }

    override val pluginId: String get() = KOTLIN_SCRIPTING_PLUGIN_ID

    override val supportsK2: Boolean
        get() = true
}

