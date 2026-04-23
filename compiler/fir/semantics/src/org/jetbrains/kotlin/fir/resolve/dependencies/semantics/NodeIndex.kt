/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies.semantics

import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.Companion.outermostEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.hasImplementation
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

sealed interface NodeIndex<out D : FirDeclaration> {

    val poisonsOnCyclicAccess: Boolean

    context(graph: DependencyGraph)
    fun belongsToEntity(entity: EnclosingEntity<*>): Boolean

    sealed interface SingletonIndex<D : FirDeclaration> : NodeIndex<D> {
        val enclosingEntity: EnclosingEntity<*>

        context(graph: DependencyGraph)
        override fun belongsToEntity(entity: EnclosingEntity<*>): Boolean = entity.outermostEntity == enclosingEntity.outermostEntity
    }

    sealed interface DeclarationIndex<D : FirDeclaration> : SingletonIndex<D> {
        val symbol: FirBasedSymbol<D>
    }

    data class PrimitivePropertyIndex(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirPropertySymbol
    ) : DeclarationIndex<FirProperty> {
        override val poisonsOnCyclicAccess: Boolean
            get() = symbol.hasInitializer && symbol.getterSymbol?.let { !it.hasImplementation } ?: true

        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}${symbol.name.asString()}"
    }

    data class AnonymousInitializerIndex(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirAnonymousInitializerSymbol
    ) : DeclarationIndex<FirAnonymousInitializer> {
        override val poisonsOnCyclicAccess: Boolean = false
        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}<init_block>"
    }

    data class FunctionLikeIndex<D : FirCallableDeclaration>(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirCallableSymbol<D>
    ) : DeclarationIndex<D> {
        override val poisonsOnCyclicAccess: Boolean = false
        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}${symbol.name.asString()}${if (symbol !is FirPropertySymbol) "()" else " (getter)"}"
    }

    sealed interface BeginSubgraphIndex<D : FirDeclaration> : SingletonIndex<D>

    data class TopLevelIndex(
        override val enclosingEntity: EnclosingEntity.File
    ) : BeginSubgraphIndex<FirFile> {
        override val poisonsOnCyclicAccess: Boolean = false
        override fun toString(): String = "<$enclosingEntity>"
    }

    data class QualifierIndex(
        override val enclosingEntity: EnclosingEntity.Object,
    ) : BeginSubgraphIndex<FirRegularClass> {
        override val poisonsOnCyclicAccess: Boolean = true
        override fun toString(): String = enclosingEntity.toString()
    }

    data class ClinitIndex(
        override val enclosingEntity: EnclosingEntity.Class
    ) : BeginSubgraphIndex<FirRegularClass> {
        override val poisonsOnCyclicAccess: Boolean = false
        override fun toString(): String = "$enclosingEntity.<clinit>"
    }

    data class EnumEntryIndex(
        override val enclosingEntity: EnclosingEntity.EnumEntry,
    ) : BeginSubgraphIndex<FirEnumEntry> {
        override val poisonsOnCyclicAccess: Boolean = true
        override fun toString(): String = enclosingEntity.toString()
    }

    data class InstancedPropertyIndex(
        override val enclosingEntity: EnclosingEntity.InstancedProperty,
    ) : BeginSubgraphIndex<FirProperty> {
        override val poisonsOnCyclicAccess: Boolean
            get() = enclosingEntity.symbol.hasInitializer && enclosingEntity.symbol.getterSymbol?.let { !it.hasImplementation } ?: true

        override fun toString(): String = enclosingEntity.toString()
    }

    data class EndSubgraphIndex<D : FirDeclaration>(
        val beginIndex: BeginSubgraphIndex<D>
    ) : SingletonIndex<D> {
        override val poisonsOnCyclicAccess: Boolean = false
        override val enclosingEntity: EnclosingEntity<*> get() = beginIndex.enclosingEntity
        override fun toString(): String = "<${beginIndex.enclosingEntity} initialized>"
    }

    data class CompositeIndex(
        val indices: Set<NodeIndex<*>>
    ) : NodeIndex<Nothing> {
        override val poisonsOnCyclicAccess: Boolean = false

        context(graph: DependencyGraph)
        override fun belongsToEntity(entity: EnclosingEntity<*>): Boolean = indices.any { it.belongsToEntity(entity) }
        override fun toString(): String = indices.joinToString(prefix = "{", postfix = "}")
    }
}
