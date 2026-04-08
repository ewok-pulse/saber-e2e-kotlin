/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies.semantics

import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

sealed interface NodeIndex<out D : FirDeclaration> {

    val canBePoisonedOnCyclicAccess: Boolean

    sealed interface DeclarationIndex<D : FirDeclaration> : NodeIndex<D> {
        val enclosingEntity: EnclosingEntity<*>

        val symbol: FirBasedSymbol<D>
    }

    data class PrimitivePropertyIndex(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirPropertySymbol
    ) : DeclarationIndex<FirProperty> {
        override val canBePoisonedOnCyclicAccess: Boolean get() = symbol.hasInitializer
        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}${symbol.name.asString()}"
    }

    data class AnonymousInitializerIndex(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirAnonymousInitializerSymbol
    ) : DeclarationIndex<FirAnonymousInitializer> {
        override val canBePoisonedOnCyclicAccess: Boolean = true
        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}<init_block>"
    }

    data class FunctionIndex<D : FirFunction>(
        override val enclosingEntity: EnclosingEntity<*>,
        override val symbol: FirFunctionSymbol<D>
    ) : DeclarationIndex<D> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String =
            "${if (enclosingEntity is EnclosingEntity.File) "" else "$enclosingEntity."}${symbol.name.asString()}()"
    }

    sealed interface BeginSubgraphIndex<D : FirDeclaration> : NodeIndex<D> {
        val enclosingEntity: EnclosingEntity<D>
    }

    data class TopLevelIndex(
        override val enclosingEntity: EnclosingEntity.File
    ) : BeginSubgraphIndex<FirFile> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String = "<$enclosingEntity>"
    }

    data class QualifierIndex(
        override val enclosingEntity: EnclosingEntity.Object,
    ) : BeginSubgraphIndex<FirRegularClass> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String = enclosingEntity.toString()
    }

    data class ClinitIndex(
        override val enclosingEntity: EnclosingEntity.Class
    ) : BeginSubgraphIndex<FirRegularClass> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String = "$enclosingEntity.<clinit>"
    }

    data class EnumEntryIndex(
        override val enclosingEntity: EnclosingEntity.EnumEntry,
    ) : BeginSubgraphIndex<FirEnumEntry> {
        override val canBePoisonedOnCyclicAccess: Boolean = true
        override fun toString(): String = enclosingEntity.toString()
    }

    data class InstancedPropertyIndex(
        override val enclosingEntity: EnclosingEntity.InstancedProperty,
    ) : BeginSubgraphIndex<FirProperty> {
        override val canBePoisonedOnCyclicAccess: Boolean get() = enclosingEntity.symbol.hasInitializer
        override fun toString(): String = enclosingEntity.toString()
    }

    data class EndSubgraphIndex<D : FirDeclaration>(
        val beginIndex: BeginSubgraphIndex<D>
    ) : NodeIndex<D> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String = "<${beginIndex.enclosingEntity} initialized>"
    }

    data class CompositeIndex(
        val indices: Set<NodeIndex<*>>
    ) : NodeIndex<Nothing> {
        override val canBePoisonedOnCyclicAccess: Boolean = false
        override fun toString(): String = indices.joinToString(prefix = "{", postfix = "}")
    }
}