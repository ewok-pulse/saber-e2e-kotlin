/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.getNonSubsumedOverriddenSymbols
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.originalIfFakeOverride
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.ScopeFunctionRequiresPrewarm
import org.jetbrains.kotlin.fir.scopes.processAllCallables
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirIntersectionCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides

data class InheritancePropagatedDeclarations(
    val orderedDeclarations: Set<FirBasedSymbol<*>>,
    val functionDeclarations: Set<FirBasedSymbol<*>>,
    val initializesWithSubtypes: Boolean
) {
    operator fun contains(symbol: FirBasedSymbol<*>): Boolean = symbol in orderedDeclarations || symbol in functionDeclarations
}

class FirInheritancePropagatedDeclarationsStorage(session: FirSession) : FirSessionComponent {

    // We ensure the non-subsumed symbols are retrieved inside the callback of the processAllProperties function
    @OptIn(DirectDeclarationsAccess::class, ScopeFunctionRequiresPrewarm::class)
    val propagatedDeclarations: FirCache<FirClassSymbol<*>, InheritancePropagatedDeclarations, DependencyGraph.Builder> =
        session.firCachesFactory.createCache { classSymbol, builder ->
            // At least resolve to the STATUS phase which ensures we have access to the super type info and the overriding info
            classSymbol.lazyResolveToPhase(FirResolvePhase.STATUS)

            // We require the use-site scope to provide us with all possible intersections and substitution overrides
            val useSiteScope = classSymbol.unsubstitutedScope(
                builder.session,
                builder.scopeSession,
                true,
                memberRequiredPhase = FirResolvePhase.STATUS
            )

            // Populate the declared declarations (properties and init blocks), and overridden properties
            // The declarations are collected recursively, respecting JVM's initialization rules
            // JVMS25 (5.5.7):
            // ... if C is a class rather than an interface, then let SC be its superclass and let SI1, ..., SIn be all superinterfaces of C
            // (whether direct or indirect) that declare at least one non-abstract, non-static method. The order of superinterfaces is given
            // by a recursive enumeration over the superinterface hierarchy of each interface directly implemented by C. For each interface I
            // directly implemented by C (in the order of the interfaces array of C), the enumeration recurs on I's superinterfaces (in the
            // order of the interfaces array of I) before returning I. ...
            val fromPrimaryConstructor = linkedSetOf<FirVariableSymbol<*>>()
            val fromBody = linkedSetOf<FirBasedSymbol<*>>()
            val functionDeclarations = mutableSetOf<FirBasedSymbol<*>>()
            val overriddenLibraryCallables = mutableSetOf<FirCallableSymbol<*>>()
            classSymbol.declarationSymbols.forEach { symbol ->
                when (symbol) {
                    // For all properties...
                    is FirPropertySymbol if (!symbol.isLibraryDeclaration || symbol.visibility == Visibilities.Public) -> {
                        if (symbol.fromPrimaryConstructor) {
                            // If they are declared in the primary constructor, add them to the primary constructor declarations
                            fromPrimaryConstructor += symbol
                        } else if (!classSymbol.classKind.isInterface || classSymbol.classKind.isInterface && symbol.hasAnyImplementation()) {
                            // If they are declared in the body, or we are caching an interface and the declaration has a default
                            // implementation, add them to the body declarations
                            fromBody += symbol
                        }
                        // Make sure we exclude all the directly overridden properties of its library super types and default methods
                        useSiteScope.processDirectOverriddenPropertiesWithBaseScope(symbol) { baseProperty, _ ->
                            if (baseProperty.isLibraryDeclaration) overriddenLibraryCallables += baseProperty
                            ProcessorAction.NEXT
                        }
                    }
                    // For all non-library init blocks, add them to the body declarations
                    is FirAnonymousInitializerSymbol if !symbol.isLibraryDeclaration -> fromBody += symbol
                    // For all named functions (no constructors), add them to the body declarations (we do not care about the order of their declaration)
                    is FirNamedFunctionSymbol if (symbol.isLibraryDeclaration && symbol.visibility == Visibilities.Public || symbol.hasBody) -> {
                        functionDeclarations += symbol
                        // Make sure we exclude all the directly overridden properties of its library super types
                        useSiteScope.processDirectOverriddenFunctionsWithBaseScope(symbol) { baseFunction, _ ->
                            if (baseFunction.isLibraryDeclaration) overriddenLibraryCallables += baseFunction
                            ProcessorAction.NEXT
                        }
                    }
                }
            }

            // Resolve intersection and substitution overrides for callables (properties and functions)
            useSiteScope.processAllCallables { callable ->
                callable.originalIfFakeOverride()?.let {
                    // Ignore all callables that we have already processed
                    if (it in fromPrimaryConstructor || it in fromBody || it in functionDeclarations || it in overriddenLibraryCallables) return@processAllCallables
                }
                // For each intersection callable...
                if (callable is FirIntersectionCallableSymbol && (!callable.isLibraryDeclaration || callable.visibility == Visibilities.Public)) {
                    context(builder) {
                        callable.getNonSubsumedOverriddenSymbols().singleOrNull()?.let { nonSubsumed ->
                            // If it has one chosen symbol...
                            callable.intersections.forEach { intersection ->
                                intersection.unwrapSubstitutionOverrides().let { unwrapped ->
                                    if (unwrapped != nonSubsumed && unwrapped.isLibraryDeclaration) {
                                        // Mark the rest of the intersected library symbols as overridden (by the chosen symbol)
                                        overriddenLibraryCallables += intersection
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val initializesWithSubtypes =
                !classSymbol.classKind.isInterface || classSymbol.classKind.isInterface && functionDeclarations.isNotEmpty()

            val orderedDeclarations = linkedSetOf<FirBasedSymbol<*>>()

            // We do not need to enumerate the supertype hierarchy recursively because it is equivalent as recursively calling the cache
            // on each directly implemented supertype
            val superTypes = classSymbol.resolvedSuperTypes.mapNotNull {
                it.fullyExpandedType(builder.session).toRegularClassSymbol(builder.session)
            }

            // Add declarations from the superclass (if exists), as it is always initialized first
            superTypes.find { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.ENUM_CLASS }?.let {
                val (superOrdered, superFunctions) = propagatedDeclarations.getValue(it, builder)
                superOrdered.forEach { declaration ->
                    if (declaration !in overriddenLibraryCallables) orderedDeclarations.add(declaration)
                }
                superFunctions.forEach { declaration ->
                    if (declaration !in overriddenLibraryCallables) functionDeclarations.add(declaration)
                }
            }

            // Add (default) declarations from the superinterfaces in order of declaration in the supertype specifiers
            // Interfaces without (declared or inherited) default methods will cache an empty set
            superTypes.filter { it.classKind == ClassKind.INTERFACE }.forEach {
                val (superOrdered, superFunctions) = propagatedDeclarations.getValue(it, builder)
                superOrdered.forEach { declaration ->
                    if (declaration !in overriddenLibraryCallables) orderedDeclarations.add(declaration)
                }
                superFunctions.forEach { declaration ->
                    if (declaration !in overriddenLibraryCallables) functionDeclarations.add(declaration)
                }
            }

            // This set will be empty for interfaces
            orderedDeclarations += fromPrimaryConstructor
            // This will contain all default methods for interfaces
            orderedDeclarations += fromBody

            InheritancePropagatedDeclarations(
                orderedDeclarations = orderedDeclarations,
                functionDeclarations = functionDeclarations,
                initializesWithSubtypes = initializesWithSubtypes
            )
        }
}

val FirSession.propagatedDeclarationsStorage: FirInheritancePropagatedDeclarationsStorage by FirSession.sessionComponentAccessor()
