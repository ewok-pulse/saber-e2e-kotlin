/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.test.cases.types

import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.types.typeCreation.AbstractTypeModificationDslTest
import org.jetbrains.kotlin.analysis.low.level.api.fir.test.configurators.LLSourceLikeTestConfigurator

@Suppress("JUnitTestCaseWithNoTests")
class FirIdeNormalAnalysisSourceModuleTypeModificationDslTest : AbstractTypeModificationDslTest() {
    override val configurator = LLSourceLikeTestConfigurator()
}
