/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import org.jetbrains.kotlin.analysis.api.components.KaSubstitutorProvider
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.fir.KaFirSession
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirGenericSubstitutor
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirMapBackedSubstitutor
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirType
import org.jetbrains.kotlin.analysis.api.fir.utils.firSymbol
import org.jetbrains.kotlin.analysis.api.impl.base.components.KaBaseSessionComponent
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.fir.resolve.calls.overloads.ConeSimpleConstraintSystemImpl
import org.jetbrains.kotlin.fir.resolve.inference.inferenceComponents
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.resolve.substitution.chain
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.substitutorForSuperType
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.asCone
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.model.safeSubstitute

internal class KaFirSubstitutorProvider(
    override val analysisSessionProvider: () -> KaFirSession,
) : KaBaseSessionComponent<KaFirSession>(), KaSubstitutorProvider, KaFirSessionComponent {
    override fun createInheritanceTypeSubstitutor(subClass: KaClassSymbol, superClass: KaClassSymbol): KaSubstitutor? {
        withValidityAssertion {
            if (subClass == superClass) return KaSubstitutor.Empty(token)

            val baseFirSymbol = subClass.firSymbol
            val superFirSymbol = superClass.firSymbol
            val inheritancePath = collectInheritancePath(baseFirSymbol, superFirSymbol) ?: return null
            val substitutors = inheritancePath.map { (type, symbol) ->
                type.substitutorForSuperType(rootModuleSession, symbol)
            }
            return when (substitutors.size) {
                0 -> KaSubstitutor.Empty(token)
                else -> {
                    val chained = substitutors.reduce { left, right -> left.chain(right) }
                    firSymbolBuilder.typeBuilder.buildSubstitutor(chained)
                }
            }
        }
    }

    private fun collectInheritancePath(
        baseSymbol: FirClassSymbol<*>,
        superSymbol: FirClassSymbol<*>,
    ): List<Pair<ConeClassLikeType, FirRegularClassSymbol>>? {
        val stack = mutableListOf<Pair<ConeClassLikeType, FirRegularClassSymbol>>()
        var result: List<Pair<ConeClassLikeType, FirRegularClassSymbol>>? = null

        fun dfs(symbol: FirClassSymbol<*>) {
            for (superType in symbol.resolvedSuperTypes) {
                if (result != null) {
                    return
                }
                if (superType !is ConeClassLikeType) continue
                val superClassSymbol = superType.toRegularClassSymbol(rootModuleSession) ?: continue
                stack += superType to superClassSymbol
                if (superClassSymbol == superSymbol) {
                    result = stack.toList()
                    check(stack.removeLast().second == superClassSymbol)
                    break
                }
                dfs(superClassSymbol)
                check(stack.removeLast().second == superClassSymbol)
            }
        }

        dfs(baseSymbol)
        return result?.reversed()
    }

    override fun createSubstitutor(mappings: Map<KaTypeParameterSymbol, KaType>): KaSubstitutor = withValidityAssertion {
        if (mappings.isEmpty()) return KaSubstitutor.Empty(token)

        val firSubstitution = buildMap {
            mappings.forEach { (ktTypeParameterSymbol, ktType) ->
                check(ktTypeParameterSymbol is KaFirTypeParameterSymbol)
                check(ktType is KaFirType)
                put(ktTypeParameterSymbol.firSymbol, ktType.coneType)
            }
        }

        return when (val coneSubstitutor = substitutorByMap(firSubstitution, analysisSession.firSession)) {
            is ConeSubstitutorByMap -> KaFirMapBackedSubstitutor(coneSubstitutor, analysisSession.firSymbolBuilder)
            else -> KaFirGenericSubstitutor(coneSubstitutor, analysisSession.firSymbolBuilder)
        }
    }

    override fun createSubtypingSubstitutor(subClass: KaClassSymbol, superType: KaClassType): KaSubstitutor? = withValidityAssertion {
        with(analysisSession) {
            val superClassSymbol = superType.expandedSymbol ?: return null
            val expandedSuperType = superType.fullyExpandedType as? KaClassType ?: return null

            if (subClass == superClassSymbol) {
                return buildSubstitutorFromTypeArguments(subClass, expandedSuperType)
            }

            val inheritanceSubstitutor = createInheritanceTypeSubstitutor(subClass, superClassSymbol) ?: return null

            val superClassTypeParameters = superClassSymbol.typeParameters
            val typeArguments = expandedSuperType.typeArguments

            if (superClassTypeParameters.size != typeArguments.size) return null

            // Collect type parameters from the subclass that need to be inferred
            val subClassTypeParameters = subClass.typeParameters.map { kaTypeParameter ->
                require(kaTypeParameter is KaFirTypeParameterSymbol)
                ConeTypeParameterLookupTag(kaTypeParameter.firSymbol)
            }

            // Create constraint system and register type variables for subclass type parameters
            val constraintSystem = ConeSimpleConstraintSystemImpl(firSession.inferenceComponents.createConstraintSystem(), firSession)
            val typeSubstitutor = constraintSystem.registerTypeVariables(subClassTypeParameters)

            // Build mappings while adding constraints to validate consistency
            val mappings = mutableMapOf<KaTypeParameterSymbol, KaType>()

            // For each type parameter in the superclass, add constraints based on variance
            for ((typeParameter, typeArgument) in superClassTypeParameters.zip(typeArguments)) {
                // TODO support star projections?
                if (typeArgument !is KaTypeArgumentWithVariance) return null

                val concreteType = typeArgument.type
                val concreteConeType = (concreteType as? KaFirType)?.coneType ?: return null

                // Get the variance from the type argument projection
                // TODO consider declaration-site variance as well
                val argumentVariance = typeArgument.variance

                // Get the substituted type: what does this superclass type parameter map to in the subclass?
                val kaSubstitutedType = inheritanceSubstitutor.substitute(buildTypeParameterType(typeParameter))
                val substitutedConeType = (kaSubstitutedType as? KaFirType)?.coneType ?: return null

                // Substitute with type variables for the constraint system
                val substitutedWithVariables = typeSubstitutor.safeSubstitute(constraintSystem.context, substitutedConeType).asCone()

                // Add constraints based on variance:
                // - OUT_VARIANCE (out): substituted <: concrete (covariant)
                // - IN_VARIANCE (in): concrete <: substituted (contravariant)
                // - INVARIANT: equality (subtype in both directions)
                // TODO how does this work with a chain of multiple types
                when (argumentVariance) {
                    Variance.OUT_VARIANCE -> constraintSystem.addSubtypeConstraint(substitutedWithVariables, concreteConeType)
                    Variance.IN_VARIANCE -> constraintSystem.addSubtypeConstraint(concreteConeType, substitutedWithVariables)
                    Variance.INVARIANT -> {
                        constraintSystem.addSubtypeConstraint(substitutedWithVariables, concreteConeType)
                        constraintSystem.addSubtypeConstraint(concreteConeType, substitutedWithVariables)
                    }
                }

                if (substitutedConeType !is ConeTypeParameterType) continue
                // TODO simplify
                val typeParameterSymbol = subClass.typeParameters.find { tp ->
                    (tp as? KaFirTypeParameterSymbol)?.firSymbol?.toLookupTag() == substitutedConeType.lookupTag
                } ?: continue

                val mappedType = computeMappedType(typeParameterSymbol, concreteType, argumentVariance) ?: return null
                mappings[typeParameterSymbol] = mappedType
            }

            return if (constraintSystem.hasContradiction()) {
                null
            } else {
                createSubstitutor(mappings)
            }
        }
    }

    /**
     * Computes the mapping type for a type parameter based on variance.
     *
     * For OUT projections, choose the more specific type between:
     * - the type parameter's upper bound
     * - the concrete type from the projection
     *
     * For example: `out Animal` with `T : Dog` -> T = Dog (bound is more specific)
     *              `out Dog` with `T : Animal` -> T = Dog (concrete is more specific)
     *
     * @return the computed mapping type, or `null` if no valid mapping exists
     */
    context(_: KaFirSession)
    private fun computeMappedType(typeParameter: KaTypeParameterSymbol, concreteType: KaType, variance: Variance): KaType? {
        if (variance != Variance.OUT_VARIANCE) {
            return concreteType
        }

        // For `out ConcreteType` with `T : Bound`, T must satisfy both constraints:
        // T <: Bound (from the type parameter declaration)
        // T <: ConcreteType (from the out projection)
        // So T must be a subtype of BOTH Bound and ConcreteType.
        // This is only possible if one is a subtype of the other.
        val upperBound = typeParameter.upperBounds.singleOrNull() // TODO multiple bounds?
        return when {
            upperBound != null && upperBound.isSubtypeOf(concreteType) -> {
                // Bound is more specific (e.g., Dog <: Animal), use the bound
                upperBound
            }
            upperBound == null || concreteType.isSubtypeOf(upperBound) -> {
                // Concrete type is more specific or no bound, use concrete type
                concreteType
            }
            else -> {
                // Neither is a subtype of the other (e.g., CharSequence and Number)
                // No valid T exists that satisfies both constraints
                null
            }
        }
    }

    private fun buildSubstitutorFromTypeArguments(classSymbol: KaClassSymbol, classType: KaClassType): KaSubstitutor? {
        val typeParameters = classSymbol.typeParameters
        val typeArguments = classType.typeArguments
        if (typeParameters.size != typeArguments.size) return null

        val mappings = buildMap {
            for ((typeParameter, typeArgument) in typeParameters.zip(typeArguments)) {
                val concreteType = typeArgument.type ?: return null
                put(typeParameter, concreteType)
            }
        }

        return createSubstitutor(mappings)
    }
}
