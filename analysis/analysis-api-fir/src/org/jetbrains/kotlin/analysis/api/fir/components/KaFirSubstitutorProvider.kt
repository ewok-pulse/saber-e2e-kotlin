/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.components

import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.components.KaSubstitutorProvider
import org.jetbrains.kotlin.analysis.api.components.KaUnificationSubstitutorPolicy
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.fir.KaFirSession
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.fir.symbols.KaFirTypeParameterSymbolBase
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirGenericSubstitutor
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirMapBackedSubstitutor
import org.jetbrains.kotlin.analysis.api.fir.types.KaFirType
import org.jetbrains.kotlin.analysis.api.fir.utils.firSymbol
import org.jetbrains.kotlin.analysis.api.impl.base.components.KaBaseSessionComponent
import org.jetbrains.kotlin.analysis.api.lifetime.withValidityAssertion
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.fir.resolve.calls.overloads.ConeSimpleConstraintSystemImpl
import org.jetbrains.kotlin.fir.resolve.inference.ConeTypeParameterBasedTypeVariable
import org.jetbrains.kotlin.fir.resolve.inference.inferenceComponents
import org.jetbrains.kotlin.fir.resolve.inference.model.ConeFixVariableConstraintPosition
import org.jetbrains.kotlin.fir.resolve.substitution.*
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.substitutorForSuperType
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.asCone
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintSystemCompletionMode
import org.jetbrains.kotlin.resolve.calls.inference.components.TypeVariableDirectionCalculator
import org.jetbrains.kotlin.resolve.calls.inference.model.InferredEmptyIntersection
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.model.safeSubstitute

internal class KaFirSubstitutorProvider(
    override val analysisSessionProvider: () -> KaFirSession
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

        val substitution = buildMap {
            mappings.forEach { (typeParameterSymbol, type) ->
                check(typeParameterSymbol is KaFirTypeParameterSymbolBase<*>)
                check(type is KaFirType)
                put(typeParameterSymbol.firSymbol, type.coneType)
            }
        }

        return when (val coneSubstitutor = substitutorByMap(substitution, analysisSession.firSession)) {
            is ConeSubstitutorByMap -> KaFirMapBackedSubstitutor(coneSubstitutor, analysisSession.firSymbolBuilder)
            else -> KaFirGenericSubstitutor(coneSubstitutor, analysisSession.firSymbolBuilder)
        }
    }

    @KaIdeApi
    override fun createUnificationSubstitutor(
        candidateType: KaType,
        targetType: KaType,
        constructionPolicy: KaUnificationSubstitutorPolicy,
    ): KaSubstitutor? = withValidityAssertion {
        createUnificationSubstitutor(listOf(candidateType to targetType), constructionPolicy)
    }

    override fun createUnificationSubstitutor(
        candidateTypesToTargetTypes: List<Pair<KaType, KaType>>,
        constructionPolicy: KaUnificationSubstitutorPolicy
    ): KaSubstitutor? = withValidityAssertion {
        with(analysisSession) {
            /**
             * Retrieves all top-level type arguments involved in the signature of [this].
             *
             * MyClass<A, Pair<A, B>, List<Int>>.unwrapTypeArguments() -> {A, B}
             */
            fun KaType.collectTypeArgumentsFromTheSignature(): Set<KaTypeParameterSymbol> {
                return when (this) {
                    is KaTypeParameterType -> setOf(symbol)
                    is KaClassType -> this.typeArguments.flatMapTo(mutableSetOf()) { typeProjection ->
                        typeProjection.type?.collectTypeArgumentsFromTheSignature() ?: emptySet()
                    }
                    is KaIntersectionType -> this.conjuncts.flatMapTo(mutableSetOf()) { it.collectTypeArgumentsFromTheSignature() }
                    is KaFlexibleType ->
                        (this.lowerBound.collectTypeArgumentsFromTheSignature() + this.upperBound.collectTypeArgumentsFromTheSignature()).toSet()
                    is KaCapturedType -> this.projection.type?.collectTypeArgumentsFromTheSignature() ?: emptySet()
                    is KaDefinitelyNotNullType -> this.original.collectTypeArgumentsFromTheSignature()
                    else -> emptySet()
                }
            }

            /**
             * Retrieves all type arguments [this] depends on. This includes direct type arguments as well as their transitive bounds.
             */
            fun KaType.getAllTypeArgumentDependencies(): Set<KaTypeParameterSymbol> {
                val processingList = collectTypeArgumentsFromTheSignature().toMutableList()
                val result = processingList.toMutableSet()
                while (processingList.isNotEmpty()) {
                    val typeArgument = processingList.pop()
                    val upperBounds = typeArgument.upperBounds.flatMap { it.collectTypeArgumentsFromTheSignature() }.ifEmpty { continue }
                    upperBounds.forEach { upperBound ->
                        if (result.add(upperBound)) {
                            processingList.add(upperBound)
                        }
                    }
                }
                return result
            }

            if (candidateTypesToTargetTypes.isEmpty()) {
                return KaSubstitutor.Empty(analysisSession.token)
            }

            val candidateTypeParameters = mutableSetOf<KaTypeParameterSymbol>()
            val targetTypeParameters = mutableSetOf<KaTypeParameterSymbol>()
            candidateTypesToTargetTypes.forEach { (candidateType, targetType) ->
                candidateTypeParameters.addAll(candidateType.getAllTypeArgumentDependencies())
                targetTypeParameters.addAll(targetType.getAllTypeArgumentDependencies())
            }

            /**
             * If types in all the pairs do not depend on any type parameters,
             * a regular [org.jetbrains.kotlin.analysis.api.components.KaTypeRelationChecker.isSubtypeOf] is called.
             */
            if (targetTypeParameters.isEmpty() && candidateTypeParameters.isEmpty()) {
                return KaSubstitutor.Empty(analysisSession.token).takeIf {
                    candidateTypesToTargetTypes.all { (candidateType, targetType) ->
                        candidateType.isSubtypeOf(targetType)
                    }
                }
            }

            /**
             * Contains all free type parameters. Free type parameters are parameters for which the constraint system will try
             * to find a mapping to satisfy the constraints.
             *
             * The final list depends on the [constructionPolicy].
             * If we have to check whether the constraints are valid for any values of the type parameters ([KaUnificationSubstitutorPolicy.UNIVERSAL]),
             * we have to exclude type parameters involved in the candidate types.
             * If we include candidate type parameters as well, the constraint system could assign any types to them to satisfy the constraint system.
             * ```kotlin
             * fun <TARGET: Number> foo(target: TARGET) {}
             *
             * fun <CANDIDATE: Any> bar(candidate: CANDIDATE) {}
             * ```
             * Here `CANDIDATE` type is not necessarily a subtype of `TARGET`. However, if we include `CANDIDATE` in the constraint system,
             * the constraint system will not have any contradictions as `CANDIDATE -> TARGET` mapping satisfies the `CANDIDATE <: TARGET` constraint.
             *
             * If we just need to find any mapping for which the constraints hold ([KaUnificationSubstitutorPolicy.EXISTENTIAL]),
             * we include candidate type parameters as well.
             */
            val allInvolvedTypeParameters = if (constructionPolicy == KaUnificationSubstitutorPolicy.UNIVERSAL) {
                targetTypeParameters - candidateTypeParameters
            } else {
                targetTypeParameters + candidateTypeParameters
            }.distinct()

            val coneTypeParameterList = allInvolvedTypeParameters.map { kaTypeParameter ->
                require(kaTypeParameter is KaFirTypeParameterSymbol)
                ConeTypeParameterLookupTag(kaTypeParameter.firSymbol)
            }

            val constraintSystem = ConeSimpleConstraintSystemImpl(firSession.inferenceComponents.createConstraintSystem(), firSession)
            val typeSubstitutor = constraintSystem.registerTypeVariables(coneTypeParameterList)
            val registeredConstraints =
                mutableListOf<Pair<ConeKotlinType, ConeKotlinType>>()

            with(constraintSystem.context) {
                candidateTypesToTargetTypes.forEach { (candidateType, targetType) ->
                    val preparedCandidateType = AbstractTypeChecker.prepareType(
                        constraintSystem.context,
                        typeSubstitutor.safeSubstitute(candidateType.coneType)
                    ).let {
                        /**
                         * Here it's important to use [org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext.captureFromExpression] whenever possible.
                         * It sets the capture status to [org.jetbrains.kotlin.types.model.CaptureStatus.FROM_EXPRESSION] for captured types
                         * instead of [org.jetbrains.kotlin.types.model.CaptureStatus.FOR_SUBTYPING],
                         * which helps to avoid approximation of captured types during the constraint calculation.
                         *
                         * Otherwise, it can produce unwanted constraints like UPPER(Nothing) and LOWER(Any?) (from CapturedType(*) <:> TypeVariable(E))
                         * in the type inference context due to the approximation of captured types.
                         *
                         * @see org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintInjector.TypeCheckerStateForConstraintInjector.addNewIncorporatedConstraint
                         */
                        constraintSystem.context.captureFromExpression(it) ?: it
                    }

                    val substitutedTargetType = typeSubstitutor.safeSubstitute(targetType.coneType)

                    registeredConstraints += preparedCandidateType.asCone() to substitutedTargetType.asCone()
                    constraintSystem.addSubtypeConstraint(preparedCandidateType, substitutedTargetType)
                }
            }

            if (constraintSystem.hasContradiction()) {
                return null
            }

            val fixedKaSubstitutorByMap = constraintSystem.fixTypeVariablesAndGetSubstitutor()

            return fixedKaSubstitutorByMap.takeIf {
                if (constructionPolicy == KaUnificationSubstitutorPolicy.UNIVERSAL && constraintSystem.hasContradiction()) {
                    /**
                     * Some errors in the system can occur during the variable fixation, so they need to be additionally checked.
                     * These should only be checked with [KaUnificationSubstitutorPolicy.UNIVERSAL].
                     * With [KaUnificationSubstitutorPolicy.EXISTENTIAL] it might procude false-positive errors.
                     * E.g., with
                     * candidateType = List<A>
                     * targetType = List<B> where B: Comparable<B>
                     * we would get
                     * A -> Any (implicit upper bound, fixed first, A <: B constraint is ignored as B is not fixed yet)
                     * which would lead to Any <: Comparable<B> constraint error.
                     */
                    return@takeIf false
                }

                if (constraintSystem.system.errors.any { error -> error is InferredEmptyIntersection }) {
                    // `constraintSystem.hasContradiction` only checks ERROR-level errors in the system.
                    // InferredEmptyIntersection might be reported with WARNING-level depending on the language settings,
                    // so it should be checked explicitly
                    return@takeIf false
                }

                val currentRawSubstitutor = constraintSystem.system.buildCurrentSubstitutor().asCone()

                /**
                 * This sanity check ensures that substituted candidate types are subtypes of the substituted target types.
                 * It's run on the raw substitutor from the constraint system and with prepared types that were registered as constraints.
                 *
                 * Prepared types are needed for the proper handling of captured types and star projections.
                 * E.g., with
                 * candidateType = A<*>
                 * targetType = A<T>
                 * the produced substitutor is
                 * T -> CapturedType(*).
                 * A<*> is not a subtype of A<CapturedType(*)> as `*` is unknown and can represent anything.
                 * However, the prepared candidate type is actually A<CapturedType(*)>,
                 * and this captured projection is actually where this `T -> CapturedType(*)` mapping comes from.
                 * So the type checker can easily verify that these two projections are the same.
                 * Just recapturing star projections is unsafe because recaptures of the same star projection are not considered equal.
                 *
                 * We operate with cone types and the cone substitutor here instead of using KaTypes and the KaSubstitutor because
                 * type parameter types are turned into [org.jetbrains.kotlin.fir.types.ConeTypeVariableType]s when prepared:
                 * T -> TypeVariable(T), A<T> -> A<TypeVariable(T)>
                 * ConeTypeVariableTypes are not supported by the Analysis API
                 * and are converted into [org.jetbrains.kotlin.analysis.api.types.KaErrorType]s in `asKaType`.
                 */
                currentRawSubstitutor.isUnificationCorrect(registeredConstraints)
            }
        }
    }

    /**
     * Fixes variables in [this] and provides a substitutor from the stored constraints.
     */
    private fun ConeSimpleConstraintSystemImpl.fixTypeVariablesAndGetSubstitutor(): KaSubstitutor {
        with(system) {
            val inferenceComponents = session.inferenceComponents

            while (notFixedTypeVariables.isNotEmpty()) {
                val variableForFixation =
                    inferenceComponents.variableFixationFinder.findFirstVariableForFixation(
                        allTypeVariables = notFixedTypeVariables.keys.toList(),
                        postponedKtPrimitives = emptyList(),
                        completionMode = ConstraintSystemCompletionMode.FULL,
                        // Can be any type. Only affects the fixation with completionMode = ConstraintSystemCompletionMode.PARTIAL
                        topLevelType = analysisSession.builtinTypes.nullableAny.coneType,
                    ) ?: break

                val variableWithConstraints = notFixedTypeVariables.getValue(variableForFixation.variable)
                val resultType = inferenceComponents.resultTypeResolver.findResultType(
                    variableWithConstraints,
                    TypeVariableDirectionCalculator.ResolveDirection.UNKNOWN,
                )

                fixVariable(
                    variableWithConstraints.typeVariable,
                    resultType,
                    ConeFixVariableConstraintPosition(variableWithConstraints.typeVariable),
                )
            }


            /**
             * The substitutor acquired from the fixation is [org.jetbrains.kotlin.fir.resolve.substitution.ConeTypeSubstitutorByTypeConstructor].
             * For debug / rendering purposes and simplicity, it has to be turned into a map-based substitutor.
             */
            val substitution = fixedTypeVariables.mapNotNull { (constructor, type) ->
                val variable = allTypeVariables[constructor] as? ConeTypeParameterBasedTypeVariable ?: return@mapNotNull null
                variable.typeParameterSymbol to type.asCone()
            }.toMap()

            val coneSubstitutorByMap = substitutorByMap(
                substitution,
                analysisSession.firSession,
                allowIdenticalSubstitution = false
            )
            return coneSubstitutorByMap.toKaSubstitutor()
        }
    }

    private fun ConeSubstitutor.isUnificationCorrect(registeredConstraints: List<Pair<ConeKotlinType, ConeKotlinType>>): Boolean {
        return registeredConstraints.all { (subType, targetType) ->
            val substitutedSubType = substituteOrSelf(subType)
            val substitutedTargetType = substituteOrSelf(targetType)
            substitutedSubType.isSubtypeOf(substitutedTargetType, this@KaFirSubstitutorProvider.analysisSession.firSession)
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
