/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies

import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.collectEnumEntries
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isEnumEntry
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.getContainingSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.processAllProperties
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

sealed class TraversalOrder {

    abstract suspend fun <T> SequenceScope<T>.traverseNext(current: T, neighbours: (T) -> Sequence<T>)

    inline fun <T> traverse(
        start: T,
        visited: MutableSet<T> = mutableSetOf(),
        crossinline predicate: (T) -> Boolean = { true },
        crossinline neighbours: (T) -> Sequence<T>
    ): Sequence<T> =
        sequence {
            when (predicate(start) && visited.add(start)) {
                true -> traverseNext(start) { next -> neighbours(next).filter { predicate(it) && visited.add(it) } }
                false -> {}
            }
        }

    object PreOrder : TraversalOrder() {
        override suspend fun <T> SequenceScope<T>.traverseNext(current: T, neighbours: (T) -> Sequence<T>) {
            yield(current)
            neighbours(current).forEach {
                traverseNext(it, neighbours)
            }
        }
    }

    object PostOrder : TraversalOrder() {
        override suspend fun <T> SequenceScope<T>.traverseNext(current: T, neighbours: (T) -> Sequence<T>) {
            neighbours(current).forEach {
                traverseNext(it, neighbours)
            }
            yield(current)
        }
    }
}

// Essentially a union find data structure which automatically unites each element on their direct parent
// NOTE: the supplier should have O(1) time complexity
open class PathCompressingFinder<T>(private val directParentSupplier: (T) -> T) {
    private val parents = mutableMapOf<T, T>()

    fun find(element: T): T {
        if (parents.getOrPut(element) { directParentSupplier(element) } == element) return element
        parents[element] = find(parents.getValue(element))
        return parents.getValue(element)
    }
}

fun <E> MutableSet<E>.join(other: Iterable<E>): MutableSet<E> = apply {
    other.forEach { add(it) }
}

fun FirClassSymbol<*>.collectEnumEntries(): List<FirEnumEntrySymbol> {
    return collectEnumEntries(moduleData.session)
}

fun FirAnonymousObjectSymbol.findCorrespondingEnumEntry(): FirEnumEntrySymbol? =
    when (isEnumEntry) {
        true -> resolvedSuperTypes.asSequence()
            .mapNotNull { it.fullyExpandedType(moduleData.session).toRegularClassSymbol(moduleData.session) }
            .find { it.isEnumClass }
            ?.let { enumClass ->
                lateinit var enumEntry: FirEnumEntrySymbol
                enumClass.declaredMemberScope(moduleData.session, memberRequiredPhase = FirResolvePhase.STATUS).processAllProperties {
                    if (it is FirEnumEntrySymbol && it.initializerObjectSymbol == this) {
                        enumEntry = it
                    }
                }
                enumEntry
            }
        false -> null
    }

val FirCallableSymbol<*>.containingFileSymbol: FirFileSymbol? get() = getContainingSymbol(moduleData.session)?.let { it as? FirFileSymbol }

val FirClassSymbol<*>.isInitializedByItsSupertypes: Boolean
    get() = !classKind.isInterface || classKind.isInterface && declarationSymbols.any {
        it is FirPropertySymbol && it.hasAnyImplementation || it is FirFunctionSymbol<*> && it.hasBody
    }

val FirPropertyAccessorSymbol.hasImplementation: Boolean get() = !isDefault && hasBody

val FirPropertySymbol.hasAnyImplementation: Boolean
    get() = (getterSymbol?.hasImplementation ?: false) || (setterSymbol?.hasImplementation ?: false)

val FirBasedSymbol<*>.isLibraryDeclaration: Boolean
    get() = origin == FirDeclarationOrigin.Library
            || origin == FirDeclarationOrigin.Java.Library
            || moduleData.session.kind == FirSession.Kind.Library
