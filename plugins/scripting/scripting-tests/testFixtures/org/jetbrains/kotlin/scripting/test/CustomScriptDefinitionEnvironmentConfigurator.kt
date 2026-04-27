/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.test

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationExtension
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

class CustomScriptDefinitionEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun legacyRegisterCompilerExtensions(project: Project, module: TestModule, configuration: CompilerConfiguration) {
        val hostConfiguration = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {}

        ScriptingCompilerConfigurationExtension(project, hostConfiguration).updateConfiguration(configuration)
    }
}
