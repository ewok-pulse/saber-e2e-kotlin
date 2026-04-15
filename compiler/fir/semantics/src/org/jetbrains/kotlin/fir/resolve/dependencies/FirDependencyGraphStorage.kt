/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies

import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.ScopeSessionHolder
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.name.Name

class FirDependencyGraphStorage(session: FirSession) : FirSessionComponent {

    val builderCache: FirCache<Name, DependencyGraph.Builder, ScopeSession> =
        session.firCachesFactory.createCache { moduleName, scopeSession -> DependencyGraph.Builder(session, scopeSession, moduleName) }
}

private val FirSession.dependencyGraphStorage: FirDependencyGraphStorage by FirSession.sessionComponentAccessor()

context(holder: ScopeSessionHolder)
val FirModuleData.dependencyGraphBuilder: DependencyGraph.Builder
    get() = session.dependencyGraphStorage.builderCache.getValue(name, holder.scopeSession)

