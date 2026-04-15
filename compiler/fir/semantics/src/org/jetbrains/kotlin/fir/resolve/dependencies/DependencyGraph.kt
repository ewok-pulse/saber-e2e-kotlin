/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.isEnumClass
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.fullyExpandedClass
import org.jetbrains.kotlin.fir.declarations.getNonSubsumedOverriddenSymbols
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isExtension
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.exceptionHandler
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirComponentCall
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirIncrementDecrementExpression
import org.jetbrains.kotlin.fir.expressions.FirIntegerLiteralOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirMultiDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirSuperReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenSubjectExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedExpression
import org.jetbrains.kotlin.fir.isGeneratedStaticEnumMember
import org.jetbrains.kotlin.fir.originalIfFakeOverride
import org.jetbrains.kotlin.fir.references.toResolvedConstructorSymbol
import org.jetbrains.kotlin.fir.references.toResolvedEnumEntrySymbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.resolve.ExplicitlyPassedSession
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.DependencyNode.Companion.accesses
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.DependencyNode.Companion.happensBefore
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.DependencyNode.Companion.mayHappenBefore
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.DependencyNode.Companion.stronglyConnectedComponents
import org.jetbrains.kotlin.fir.resolve.dependencies.DependencyGraph.DependencyNode.CondensedNode.Companion.condense
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.Dependency
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asClassEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asEnumEntryEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asFileEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asInstancedPropertyEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.asObjectEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.EnclosingEntity.Companion.outermostEntity
import org.jetbrains.kotlin.fir.resolve.dependencies.semantics.NodeIndex
import org.jetbrains.kotlin.fir.resolve.dfa.Stack
import org.jetbrains.kotlin.fir.resolve.dfa.isNotEmpty
import org.jetbrains.kotlin.fir.resolve.dfa.stackOf
import org.jetbrains.kotlin.fir.resolve.dfa.topOrNull
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.getContainingFile
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.ScopeFunctionRequiresPrewarm
import org.jetbrains.kotlin.fir.scopes.processAllCallables
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousInitializerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirIntersectionCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.types.toRegularClassSymbol
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.Printer
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue
import org.jetbrains.kotlin.utils.mapToIndex
import java.util.LinkedList
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.contains
import kotlin.collections.set
import kotlin.sequences.forEach

data class DependencyGraph(
    private val nodes: MutableMap<NodeIndex<*>, DependencyNode<*>> = linkedMapOf(),
    private val entities: MutableMap<EnclosingEntity<*>, MutableSet<NodeIndex<*>>> = linkedMapOf()
) : FirSessionComponent, Set<DependencyGraph.DependencyNode<*>> {

    private val poisonedNodes = mutableSetOf<NodeIndex<*>>()

    val enclosingEntities: Set<EnclosingEntity<*>> get() = entities.keys

    override val size: Int get() = nodes.size

    override fun isEmpty(): Boolean = nodes.isEmpty()

    override fun contains(element: DependencyNode<*>): Boolean = element.index in this

    override fun iterator(): Iterator<DependencyNode<*>> = nodes.values.iterator()

    override fun containsAll(elements: Collection<DependencyNode<*>>): Boolean = elements.all { it in this }

    operator fun get(index: NodeIndex<*>): DependencyNode<*>? = nodes[index]

    operator fun get(enclosingEntity: EnclosingEntity<*>): Sequence<NodeIndex<*>> =
        entities[enclosingEntity]?.asSequence() ?: emptySequence()

    operator fun contains(index: NodeIndex<*>): Boolean = index in nodes

    operator fun contains(enclosingEntity: EnclosingEntity<*>): Boolean = enclosingEntity in enclosingEntities

    fun poisoningAccessesFor(index: NodeIndex<*>, visited: MutableSet<NodeIndex<*>> = mutableSetOf()): Set<FirExpression> {
        require(index in poisonedNodes)
        return this[index]?.let { node ->
            when (node) {
                is DependencyNode.CompositeNode -> mutableSetOf<FirExpression>().apply {
                    node.accessesFor(index).forEach { (from, exprs) ->
                        if (index.canBePoisonedOnCyclicAccess && from in node
                            || from.isInPossiblyUninitializedEntity(node)
                            || isPoisoned(from, visited)
                        ) {
                            addAll(exprs)
                        }
                    }
                }
                is DependencyNode.SingletonNode -> mutableSetOf<FirExpression>().apply {
                    node.accesses.forEach { (from, exprs) ->
                        if (isPoisoned(from, visited)) {
                            addAll(exprs)
                        }
                    }
                }
            }
        } ?: emptySet()
    }

    fun isPoisoned(index: NodeIndex<*>, visited: MutableSet<NodeIndex<*>> = mutableSetOf()): Boolean {
        if (index !in this) return false
        if (index in poisonedNodes) return true
        if (index in visited) {
            poisonedNodes += index
            return true
        }
        // Visit the index and check its accesses
        visited.add(index)
        return this[index]?.let { node ->
            when (node) {
                // If it belongs to a composite node, ...
                is DependencyNode.CompositeNode -> {
                    val accesses = node.accessesFor(index)
                    var hasInCycleAccess = false
                    var hasAccessCausingExceptionInInitializer = false
                    val uncheckedIndices = mutableSetOf<NodeIndex<*>>()
                    for ((from, _) in accesses) {
                        if (index.canBePoisonedOnCyclicAccess && from in node) {
                            hasInCycleAccess = true
                            break
                        }
                        if (from.isInPossiblyUninitializedEntity(node)) {
                            hasAccessCausingExceptionInInitializer = true
                            break
                        }
                        uncheckedIndices.add(from)
                    }
                    // Base case: if any information flowing to the node is from a node in its own strong component (time loop)
                    // or there is an access to a possibly-uninitialized entity
                    if (hasInCycleAccess || hasAccessCausingExceptionInInitializer) {
                        poisonedNodes += index
                        return@let true
                    }
                    // Inductive step: if any information flowing to the node is from a node that is bad (elsewhere)
                    if (uncheckedIndices.any { isPoisoned(it, visited) }) {
                        poisonedNodes += index
                        return@let true
                    }
                    false
                }
                // If it belongs to a singleton node, ...
                is DependencyNode.SingletonNode<*> -> {
                    // Inductive step: if any information flowing to the node is from a node that is bad (elsewhere)
                    if (node.accesses.any { (from, _) -> isPoisoned(from, visited) }) {
                        poisonedNodes += index
                        return@let true
                    }
                    false
                }
            }
        } ?: false
    }

    context(holder: SessionAndScopeSessionHolder)
    fun deadlockingEntities(enclosingEntity: EnclosingEntity<*>): Sequence<EnclosingEntity<*>> =
        this[enclosingEntity].mapNotNull(::get)
            .filterIsInstance<DependencyNode.CondensedNode>()
            .flatMap { it.enclosingEntities }
            .map { if (it is EnclosingEntity.InstancedProperty) it.outermostEntity else it }
            .filter { it != enclosingEntity }
            .distinct()

    override fun toString(): String {
        val builder = StringBuilder()
        Printer(builder).apply {
            println("digraph DependencyGraph {")
            pushIndent()
            println("graph [overlap = true, fontsize = 10]")
            val nodes = mapToIndex()
            nodes.forEach { (node, index) ->
                println(
                    "n$index [shape=${
                        when (node.index) {
                            is NodeIndex.PrimitivePropertyIndex,
                            is NodeIndex.FunctionLikeIndex<*>,
                            is NodeIndex.QualifierIndex,
                            is NodeIndex.EnumEntryIndex,
                            is NodeIndex.CompositeIndex,
                            is NodeIndex.InstancedPropertyIndex
                                -> "circle"
                            is NodeIndex.AnonymousInitializerIndex,
                            is NodeIndex.ClinitIndex,
                            is NodeIndex.TopLevelIndex,
                            is NodeIndex.EndSubgraphIndex<*>
                                -> "box"
                        }
                    }, label=\"${node.renderAsString()}\"]"
                )
            }
            println()
            nodes.forEach { (node, index) ->
                node.outgoing.forEach { dependency ->
                    this@DependencyGraph[dependency.to]?.let(nodes::get)?.let { childIndex ->
                        println(
                            "n$index -> n$childIndex [color=${
                                when (dependency) {
                                    is Dependency.Access -> "blue"
                                    is Dependency.HappensBefore -> "red"
                                    is Dependency.MayHappenBefore -> "green"
                                }
                            }]"
                        )
                    }
                }
            }
            popIndent()
            println("}")
        }
        return builder.toString()
    }

    companion object {
        // Accessing an entity in a cycle which can be possibly uninitialized happens iff its outermost entity is a class and:
        // - the outermost class entity is an interface (KT-20238)
        // - OR the class' begin node is in the cycle as well
        private fun EnclosingEntity<*>.isAccessedPossiblyUninitialized(cycle: DependencyNode.CompositeNode): Boolean =
            parentEnclosingEntity?.let { outer ->
                // The only entities whose their outer entity is a class are companion objects and enum entries (which must be in the cycle)
                // If this is not the case, recurse further up the hierarchy (i.e., we are looking at an instanced property (not) in the cycle)
                this in cycle && outer is EnclosingEntity.Class && (outer.symbol.isInterface || outer.beginSubgraphIndex in cycle)
                        || outer.isAccessedPossiblyUninitialized(cycle)
            } ?: false

        private fun NodeIndex<*>.isInPossiblyUninitializedEntity(cycle: DependencyNode.CompositeNode): Boolean =
            // Only consider accessible nodes
            when (this) {
                is NodeIndex.DeclarationIndex -> enclosingEntity.isAccessedPossiblyUninitialized(cycle)
                is NodeIndex.BeginSubgraphIndex -> enclosingEntity.isAccessedPossiblyUninitialized(cycle)
                else -> false
            }
    }

    sealed class DependencyNode<out D : FirDeclaration> {

        abstract val index: NodeIndex<D>

        abstract val isComposite: Boolean

        abstract val incoming: Sequence<Dependency>

        abstract val outgoing: Sequence<Dependency>

        abstract val happenBefore: Sequence<NodeIndex<*>>

        abstract val possiblyHappenBefore: Sequence<NodeIndex<*>>

        abstract val happenAfter: Sequence<NodeIndex<*>>

        abstract val possiblyHappenAfter: Sequence<NodeIndex<*>>

        abstract fun accessesTo(from: NodeIndex<*>): Set<FirExpression>

        protected abstract fun addIncomingAccess(access: Dependency.Access, at: FirExpression): Boolean

        protected abstract fun addIncomingAccess(access: Dependency.Access, at: Set<FirExpression>): Boolean

        protected abstract fun addOutgoingAccess(access: Dependency.Access): Boolean

        protected abstract fun addIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean

        protected abstract fun removeIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean

        protected abstract fun addOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean

        protected abstract fun removeOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean

        fun renderAsString(): String = index.toString()

        sealed class SingletonNode<out D : FirDeclaration> : DependencyNode<D>() {
            abstract val enclosingEntity: EnclosingEntity<*>
            override val isComposite: Boolean = false

            private val _incomingInfoFlow = linkedMapOf<Dependency.Access, MutableSet<FirExpression>>()
            private val _incomingTimeFlow = linkedSetOf<Dependency.TimeDependency>()
            override val incoming: Sequence<Dependency>
                get() = sequence {
                    yieldAll(_incomingInfoFlow.keys)
                    yieldAll(_incomingTimeFlow)
                }

            private val _outgoingInfoFlow = linkedSetOf<Dependency.Access>()
            private val _outgoingTimeFlow = linkedSetOf<Dependency.TimeDependency>()
            override val outgoing: Sequence<Dependency>
                get() = sequence {
                    yieldAll(_outgoingInfoFlow)
                    yieldAll(_outgoingTimeFlow)
                }

            override val happenBefore: Sequence<NodeIndex<*>>
                get() = _incomingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::alwaysHappens)
                    .map(Dependency::from)

            override val possiblyHappenBefore: Sequence<NodeIndex<*>>
                get() = _incomingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::possiblyHappens)
                    .map(Dependency::from)

            override val happenAfter: Sequence<NodeIndex<*>>
                get() = _outgoingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::alwaysHappens)
                    .map(Dependency::to)

            override val possiblyHappenAfter: Sequence<NodeIndex<*>>
                get() = _outgoingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::possiblyHappens)
                    .map(Dependency::to)

            override fun accessesTo(from: NodeIndex<*>): Set<FirExpression> =
                _incomingInfoFlow[Dependency.Access(from, index)] ?: emptySet()

            val accesses: Sequence<Pair<NodeIndex<*>, Set<FirExpression>>>
                get() = _incomingInfoFlow.asSequence().map { (access, accesses) -> access.from to accesses }

            override fun addIncomingAccess(access: Dependency.Access, at: FirExpression): Boolean {
                if (access.to != index) return false
                return _incomingInfoFlow.getOrPut(access) { mutableSetOf() }.add(at)
            }

            override fun addIncomingAccess(access: Dependency.Access, at: Set<FirExpression>): Boolean {
                if (access.to != index) return false
                return _incomingInfoFlow.getOrPut(access) { mutableSetOf() }.addAll(at)
            }

            override fun addOutgoingAccess(access: Dependency.Access): Boolean {
                if (access.from != index) return false
                return _outgoingInfoFlow.add(access)
            }

            override fun addIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.to != index) return false
                return _incomingTimeFlow.add(timeDependency)
            }

            override fun removeIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.to != index) return false
                return _incomingTimeFlow.remove(timeDependency)
            }

            override fun addOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.from != index) return false
                return _outgoingTimeFlow.add(timeDependency)
            }

            override fun removeOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.from != index) return false
                return _outgoingTimeFlow.remove(timeDependency)
            }
        }

        sealed class CompositeNode : DependencyNode<Nothing>(), Set<NodeIndex<*>> {
            abstract override val index: NodeIndex.CompositeIndex
            override val isComposite: Boolean = true

            private val _incomingAccesses = linkedMapOf<Dependency.Access, MutableSet<FirExpression>>()
            private val _incomingInfoFlow = linkedMapOf<NodeIndex<*>, MutableSet<Dependency.Access>>()
            private val _incomingTimeFlow = linkedSetOf<Dependency.TimeDependency>()
            override val incoming: Sequence<Dependency>
                get() = sequence {
                    for ((_, accesses) in _incomingInfoFlow) {
                        yieldAll(accesses)
                    }
                    yieldAll(_incomingTimeFlow)
                }

            private val _outgoingInfoFlow = linkedMapOf<NodeIndex<*>, MutableSet<Dependency.Access>>()
            private val _outgoingTimeFlow = linkedSetOf<Dependency.TimeDependency>()
            override val outgoing: Sequence<Dependency>
                get() = sequence {
                    for ((_, accesses) in _outgoingInfoFlow) {
                        yieldAll(accesses)
                    }
                    yieldAll(_outgoingTimeFlow)
                }

            override val happenBefore: Sequence<NodeIndex<*>>
                get() = _incomingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::alwaysHappens)
                    .map(Dependency::from)

            override val possiblyHappenBefore: Sequence<NodeIndex<*>>
                get() = _incomingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::possiblyHappens)
                    .map(Dependency::from)

            override val happenAfter: Sequence<NodeIndex<*>>
                get() = _outgoingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::alwaysHappens)
                    .map(Dependency::to)

            override val possiblyHappenAfter: Sequence<NodeIndex<*>>
                get() = _outgoingTimeFlow.asSequence()
                    .filter(Dependency.TimeDependency::possiblyHappens)
                    .map(Dependency::to)

            override fun accessesTo(from: NodeIndex<*>): Set<FirExpression> =
                fold(linkedSetOf()) { acc, index ->
                    _incomingAccesses[Dependency.Access(from, index)]?.apply(acc::addAll)
                    acc
                }

            fun accessesFor(index: NodeIndex<*>): Sequence<Pair<NodeIndex<*>, Set<FirExpression>>> =
                _incomingInfoFlow[index]?.asSequence()?.mapNotNull { access ->
                    _incomingAccesses[access]?.let { exprs -> access.from to exprs }
                } ?: emptySequence()

            abstract val enclosingEntities: Set<EnclosingEntity<*>>
            abstract fun get(enclosingEntity: EnclosingEntity<*>): Sequence<NodeIndex<*>>
            operator fun contains(enclosingEntity: EnclosingEntity<*>): Boolean = enclosingEntity in enclosingEntities

            override fun addIncomingAccess(access: Dependency.Access, at: FirExpression): Boolean {
                if (access.to !in this@CompositeNode) return false
                val addedFlow = _incomingInfoFlow.getOrPut(access.to) { linkedSetOf() }.add(access)
                val addedAt = _incomingAccesses.getOrPut(access) { linkedSetOf() }.add(at)
                return addedFlow || addedAt
            }

            override fun addIncomingAccess(access: Dependency.Access, at: Set<FirExpression>): Boolean {
                if (access.to !in this@CompositeNode) return false
                val addedFlow = _incomingInfoFlow.getOrPut(access.to) { linkedSetOf() }.add(access)
                val addedAt = _incomingAccesses.getOrPut(access) { linkedSetOf() }.addAll(at)
                return addedFlow || addedAt
            }

            override fun addOutgoingAccess(access: Dependency.Access): Boolean {
                if (access.from !in this@CompositeNode) return false
                return _outgoingInfoFlow.getOrPut(access.from) { linkedSetOf() }.add(access)
            }

            override fun addIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.to != index) return false
                return _incomingTimeFlow.add(timeDependency)
            }

            override fun removeIncomingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.to != index) return false
                return _incomingTimeFlow.remove(timeDependency)
            }

            override fun addOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.from != index) return false
                return _outgoingTimeFlow.add(timeDependency)
            }

            override fun removeOutgoingTimeDependency(timeDependency: Dependency.TimeDependency): Boolean {
                if (timeDependency.from != index) return false
                return _outgoingTimeFlow.remove(timeDependency)
            }
        }

        data class DeclarationNode<D : FirDeclaration>(override val index: NodeIndex.DeclarationIndex<D>) : SingletonNode<D>() {
            override val enclosingEntity: EnclosingEntity<*> get() = index.enclosingEntity
        }

        data class BeginInitializationNode<D : FirDeclaration>(override val enclosingEntity: EnclosingEntity<D>) : SingletonNode<D>() {
            override val index: NodeIndex.BeginSubgraphIndex<D> get() = enclosingEntity.beginSubgraphIndex
        }

        data class EndInitializationNode<D : FirDeclaration>(override val enclosingEntity: EnclosingEntity<D>) : SingletonNode<D>() {
            override val index: NodeIndex.EndSubgraphIndex<D> = enclosingEntity.endSubgraphIndex
        }

        data class CondensedNode(
            private val indices: Set<NodeIndex<*>>,
            private val entities: MutableMap<EnclosingEntity<*>, MutableSet<NodeIndex<*>>>
        ) : CompositeNode(), Set<NodeIndex<*>> by indices {
            override val index: NodeIndex.CompositeIndex = NodeIndex.CompositeIndex(indices)
            override val enclosingEntities: Set<EnclosingEntity<*>> get() = entities.keys
            override operator fun get(enclosingEntity: EnclosingEntity<*>): Sequence<NodeIndex<*>> =
                entities[enclosingEntity]?.asSequence() ?: emptySequence()

            override operator fun contains(element: NodeIndex<*>): Boolean = element in indices

            companion object {
                context(graph: DependencyGraph)
                fun Set<DependencyNode<*>>.condense(): CondensedNode = CondensedNode(
                    indices = this.mapTo(linkedSetOf(), DependencyNode<*>::index),
                    entities = mutableMapOf<EnclosingEntity<*>, MutableSet<NodeIndex<*>>>().also { entities ->
                        this.forEach { node ->
                            when (node) {
                                is SingletonNode<*> -> entities.getOrPut(node.enclosingEntity) { linkedSetOf() }.add(node.index)
                                is CondensedNode -> entities.putAll(node.entities)
                            }
                        }
                    }
                ).apply {
                    // Add the node to the graph
                    graph.nodes[index] = this
                    // For each node in the set that was condensed, ...
                    this@condense.forEach { node ->
                        // For each incoming dependency, ...
                        node.incoming.forEach { dependency ->
                            when (dependency) {
                                // Add all incoming access edges from each node (regardless if their targets are in the set of not)
                                is Dependency.Access -> addIncomingAccess(dependency, node.accessesTo(dependency.from))
                                // Merge all incoming time dependencies into the new condensed node and update their targets
                                is Dependency.HappensBefore -> {
                                    // Skip edges which connect nodes in the set
                                    if (dependency.from in this) return@forEach
                                    val newDependency = Dependency.HappensBefore(dependency.from, index)
                                    addIncomingTimeDependency(newDependency)
                                    graph[dependency.from]?.let { from ->
                                        from.removeOutgoingTimeDependency(dependency)
                                        from.addOutgoingTimeDependency(newDependency)
                                    }
                                }
                                is Dependency.MayHappenBefore -> {
                                    // Skip edges which connect nodes in the set
                                    if (dependency.from in this) return@forEach
                                    val newDependency = Dependency.MayHappenBefore(dependency.from, index)
                                    addIncomingTimeDependency(newDependency)
                                    graph[dependency.from]?.let { from ->
                                        from.removeOutgoingTimeDependency(dependency)
                                        from.addOutgoingTimeDependency(newDependency)
                                    }
                                }
                            }
                        }
                        // For each outgoing dependency, ...
                        node.outgoing.forEach { dependency ->
                            when (dependency) {
                                // Add all outgoing access edges from each node (regardless if their targets are in the set of not)
                                is Dependency.Access -> addOutgoingAccess(dependency)
                                // Merge all incoming time dependencies into the new condensed node and update their targets
                                is Dependency.HappensBefore -> {
                                    // Skip edges which connect nodes in the set
                                    if (dependency.to in this) return@forEach
                                    val newDependency = Dependency.HappensBefore(index, dependency.to)
                                    addOutgoingTimeDependency(newDependency)
                                    graph[dependency.from]?.let { from ->
                                        from.removeIncomingTimeDependency(dependency)
                                        from.addIncomingTimeDependency(newDependency)
                                    }
                                }
                                is Dependency.MayHappenBefore -> {
                                    // Skip edges which connect nodes in the set
                                    if (dependency.to in this) return@forEach
                                    val newDependency = Dependency.MayHappenBefore(index, dependency.to)
                                    addOutgoingTimeDependency(newDependency)
                                    graph[dependency.from]?.let { from ->
                                        from.removeIncomingTimeDependency(dependency)
                                        from.addIncomingTimeDependency(newDependency)
                                    }
                                }
                            }
                        }
                        // Update the graph's indices
                        when (node) {
                            // For singleton nodes, we keep the mapping of their node indices to this condensed node,
                            // as we require their presence in the graph for further analysis of their accesses
                            is SingletonNode<*> -> graph.nodes[node.index] = this
                            // For composite nodes, they are only preserved through time dependencies, so once the node
                            // is detached, it has no accesses by itself and can be safely removed from the graph
                            is CompositeNode -> graph.nodes.remove(node.index)
                        }
                    }
                }
            }
        }

        companion object {

            context(graph: DependencyGraph)
            infix fun NodeIndex<*>?.accesses(access: Pair<NodeIndex<*>, Set<FirExpression>>): Boolean {
                val (other, at) = access
                // Disallow self-loops
                if (this != null && this != other && this in graph && other in graph) {
                    val dependency = Dependency.Access(other, this)
                    val addedLeft = graph[this]?.addIncomingAccess(dependency, at) ?: false
                    val addedRight = graph[other]?.addOutgoingAccess(dependency) ?: false
                    return addedLeft || addedRight
                }
                return false
            }

            context(graph: DependencyGraph)
            infix fun NodeIndex<*>?.happensBefore(other: NodeIndex<*>): Boolean {
                // Disallow self-loops
                if (this != null && this != other && this in graph && other in graph) {
                    val actualLeftIndex = graph[this]?.index ?: return false
                    val actualRightIndex = graph[other]?.index ?: return false
                    val dependency = Dependency.HappensBefore(actualLeftIndex, actualRightIndex)
                    val addedLeft = graph[this]?.addOutgoingTimeDependency(dependency) ?: false
                    val addedRight = graph[other]?.addIncomingTimeDependency(dependency) ?: false
                    return addedLeft || addedRight
                }
                return false
            }

            context(graph: DependencyGraph)
            infix fun NodeIndex<*>?.mayHappenBefore(other: NodeIndex<*>): Boolean {
                // Disallow self-loops
                if (this != null && this != other && this in graph && other in graph) {
                    val actualLeftIndex = graph[this]?.index ?: return false
                    val actualRightIndex = graph[other]?.index ?: return false
                    val dependency = Dependency.MayHappenBefore(actualLeftIndex, actualRightIndex)
                    val addedLeft = graph[this]?.addOutgoingTimeDependency(dependency) ?: false
                    val addedRight = graph[other]?.addIncomingTimeDependency(dependency) ?: false
                    return addedLeft || addedRight
                }
                return false
            }

            context(graph: DependencyGraph)
            inline fun DependencyNode<*>.possiblyHappenAncestors(
                visited: MutableSet<DependencyNode<*>> = mutableSetOf(),
                traversalOrder: TraversalOrder = TraversalOrder.PreOrder,
                crossinline predicate: (DependencyNode<*>) -> Boolean = { true }
            ): Sequence<DependencyNode<*>> =
                traversalOrder.traverse(
                    start = this@possiblyHappenAncestors,
                    visited = visited,
                    predicate = predicate,
                    neighbours = { it.possiblyHappenBefore.mapNotNull(graph::get) }
                )

            context(graph: DependencyGraph)
            inline fun DependencyNode<*>.possiblyHappenDescendants(
                visited: MutableSet<DependencyNode<*>> = mutableSetOf(),
                traversalOrder: TraversalOrder = TraversalOrder.PreOrder,
                crossinline predicate: (DependencyNode<*>) -> Boolean = { true }
            ): Sequence<DependencyNode<*>> =
                traversalOrder.traverse(
                    start = this@possiblyHappenDescendants,
                    visited = visited,
                    predicate = predicate,
                    neighbours = { it.possiblyHappenAfter.mapNotNull(graph::get) }
                )

            context(graph: DependencyGraph)
            fun Set<DependencyNode<*>>.stronglyConnectedComponents(): List<Set<DependencyNode<*>>> {
                val visited = mutableSetOf<DependencyNode<*>>()
                val sorted = stackOf<DependencyNode<*>>()
                this@stronglyConnectedComponents.forEach { node ->
                    node.possiblyHappenDescendants(visited, TraversalOrder.PostOrder) { it in this }
                        .forEach(sorted::push)
                }
                visited.clear()

                val result = LinkedList<Set<DependencyNode<*>>>()
                while (sorted.isNotEmpty) {
                    val current = sorted.pop()
                    if (current !in visited) {
                        val component = mutableSetOf<DependencyNode<*>>()
                        current.possiblyHappenAncestors(visited, TraversalOrder.PostOrder) { it in this }
                            .forEach { component += it }
                        result += component
                    }
                }

                return result
            }
        }
    }

    class Builder(
        override val session: FirSession,
        override val scopeSession: ScopeSession,
        val moduleName: Name,
    ) : SessionAndScopeSessionHolder {

        val graph: DependencyGraph = DependencyGraph()

        /**
         * A set of all files already visited (or currently visiting) by the builder
         */
        private val visitedFiles: MutableSet<FirFileSymbol> = mutableSetOf()

        private sealed interface SymbolReference<D : FirDeclaration> {
            val symbol: FirBasedSymbol<D>

            val isStatic: Boolean get() = false

            /**
             * Directly referenced static declarations
             */
            data class NodeReference<D : FirDeclaration>(val index: NodeIndex.DeclarationIndex<D>) : SymbolReference<D> {
                override val symbol: FirBasedSymbol<D> get() = index.symbol
                override val isStatic: Boolean = true
            }

            /**
             * Directly referenced the subgraph of an entity (of an enum entry or an instanced property)
             */
            data class SubgraphReference<D : FirDeclaration>(val enclosingEntity: EnclosingEntity<D>) : SymbolReference<D> {
                override val symbol: FirBasedSymbol<D> get() = enclosingEntity.symbol
                override val isStatic: Boolean = true
            }

            /**
             * Directly referenced a class symbol of an entity
             *
             * Used specifically to ensure may-happen-before relations when due to GetClass calls and class references
             */
            data class ClassReference<D : FirClass>(val enclosingEntity: EnclosingEntity<D>) : SymbolReference<D> {
                override val symbol: FirBasedSymbol<D> get() = enclosingEntity.symbol
                override val isStatic: Boolean = true
            }

            /**
             * Directly referenced a member symbol
             */
            data class MemberReference<D : FirDeclaration>(override val symbol: FirBasedSymbol<D>) : SymbolReference<D>

            data class CapturedReference<D : FirDeclaration>(override val symbol: FirBasedSymbol<D>) : SymbolReference<D>
        }

        /**
         * Cache static accesses for (dynamic) member declarations to avoid visiting the declaration multiple times when encountered
         */
        private val symbolReferences =
            mutableMapOf<FirBasedSymbol<*>, MutableMap<SymbolReference<*>, MutableSet<FirExpression>>>()

        private data class AccessEvaluationContext(
            val enclosingEntity: EnclosingEntity<*>,
            val accessingNode: NodeIndex<*>,
            val visited: MutableSet<FirBasedSymbol<*>> = mutableSetOf(),
            val isDynamic: Boolean = false,
        )

        /**
         * Mark each callable declaration pending body resolution and keep track of all the nodes that require its dependencies
         *
         * This cache is empty in the case the builder is run using Analysis-API
         */
        private val pendingResolution = mutableMapOf<FirFileSymbol, MutableMap<FirBasedSymbol<*>, MutableSet<AccessEvaluationContext>>>()

        private inner class SymbolReferenceCollector : FirVisitorVoid() {

            private val symbolStack: Stack<FirBasedSymbol<*>> = stackOf()
            private val elementStack: Stack<FirElement> = stackOf()

            private inline fun <E : FirElement> E.visit(containingSymbol: FirBasedSymbol<*>? = null, crossinline block: E.() -> Unit) {
                var popSymbol = false
                if (this is FirDeclaration) {
                    // If the declaration is contained in a file that has not been visited yet, i.e., has not been resolved yet, ...
                    session.firProvider.getContainingFile(containingSymbol ?: symbol)
                        ?.takeIf { it.symbol !in visitedFiles }
                        ?.let { file ->
                            // Store its symbol as pending resolution under its containing file, and mark the node that depends on its resolution
                            pendingResolution.getOrPut(file.symbol) { mutableMapOf() }
                                .getOrPut(symbol) { mutableSetOf() }
                            return@visit
                        }
                    // If there are already collected references for this declaration, do not collect them again
                    if (symbol in symbolReferences) return
                    // Otherwise, push its symbol to the top of the stack
                    symbolStack.push(symbol)
                    // Even if the map is empty, it indicates that the symbol has been visited
                    symbolReferences[symbol] = mutableMapOf()
                    popSymbol = true
                }
                elementStack.push(this)
                try {
                    block()
                } catch (e: Throwable) {
                    session.exceptionHandler.handleExceptionOnElementAnalysis(this, e)
                } finally {
                    elementStack.pop()
                    if (popSymbol) symbolStack.pop()
                }
            }

            private fun addReferenceToCurrent(reference: SymbolReference<*>, at: FirExpression) =
                symbolStack.topOrNull()?.let { currentSymbol ->
                    symbolReferences.getValue(currentSymbol)
                        .getOrPut(reference) { mutableSetOf() }
                        .add(at)
                }

            private fun FirElement.collect(): Unit = accept(this@SymbolReferenceCollector)

            private fun FirElement.collectRecursively(): Unit = acceptChildren(this@SymbolReferenceCollector)

            override fun visitElement(element: FirElement) = Unit

            /**
             * =============================================
             *             Visiting properties
             * =============================================
             */

            override fun visitProperty(property: FirProperty) {
                property.visit {
                    // Visit only the initializer and a getter
                    initializer?.collect()
                    getter?.collectRecursively()
                }
            }

            override fun visitBlock(block: FirBlock) = block.visit { collectRecursively() }

            /**
             * =============================================
             *             Visiting functions
             * =============================================
             */

            override fun visitFunction(function: FirFunction) = function.visit {
                valueParameters.forEach { it.defaultValue?.collect() }
                body?.collect()
            }

            override fun visitNamedFunction(namedFunction: FirNamedFunction) = visitFunction(namedFunction)

            // Forward to visitFunction
            override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) = visitFunction(anonymousFunction)

            override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression) =
                anonymousFunctionExpression.visit {
                    addReferenceToCurrent(SymbolReference.CapturedReference(anonymousFunction.symbol), anonymousFunctionExpression)
                    anonymousFunction.collect()
                }

            /**
             * =============================================
             *            Visiting initializers
             * =============================================
             */

            override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer) =
                anonymousInitializer.visit(containingSymbol = anonymousInitializer.containingDeclarationSymbol) { body?.collect() }

            /**
             * =============================================
             *            Visiting constructors
             * =============================================
             */

            override fun visitConstructor(constructor: FirConstructor): Unit = constructor.visit {
                delegatedConstructor?.collect()
                body?.collect()
            }

            /**
             * =============================================
             *         Visiting qualified accesses
             * =============================================
             */

            private fun FirResolvedQualifier.toEnclosingEntity(): EnclosingEntity<FirRegularClass>? = symbol?.let { symbol ->
                symbol.fullyExpandedClass(symbol.moduleData.session)?.let {
                    if (resolvedToCompanionObject) {
                        it.resolvedCompanionObjectSymbol?.asObjectEntity(it.asClassEntity())
                    } else if (it.classKind.isObject) {
                        it.asObjectEntity()
                    } else {
                        it.asClassEntity()
                    }
                }
            }

            private fun FirPropertyAccessExpression.toEnclosingEntity(): EnclosingEntity<*>? =
                calleeReference.toResolvedEnumEntrySymbol(discardErrorReference = true)?.asEnumEntryEntity()
                    ?: calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { propertySymbol ->
                        if (propertySymbol.isLocal || !propertySymbol.hasInitializer) return null
                        // There can be no enclosing entity corresponding to a primitive property
                        if (propertySymbol.resolvedReturnType.let { it.isPrimitiveOrNullablePrimitive || it.isUnit || it.isNothing }) return@let null
                        val enclosingEntity = dispatchReceiver?.let { receiver ->
                            when (receiver) {
                                is FirResolvedQualifier -> receiver.toEnclosingEntity()
                                is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                                else -> null
                            }
                        } ?: propertySymbol.containingFileSymbol?.asFileEntity()
                        println("Got enclosing entity: $enclosingEntity")
                        enclosingEntity?.let { propertySymbol.asInstancedPropertyEntity(it) }
                    }

            private fun FirPropertySymbol.toNodeReference(enclosingEntity: EnclosingEntity<*>): SymbolReference<FirProperty> =
                when (hasInitializer) {
                    true -> when (resolvedReturnType.isPrimitiveOrNullablePrimitive || resolvedReturnType.isUnit || resolvedReturnType.isNothing) {
                        true -> SymbolReference.NodeReference(NodeIndex.PrimitivePropertyIndex(enclosingEntity, this))
                        false -> SymbolReference.SubgraphReference(asInstancedPropertyEntity(enclosingEntity))
                    }
                    false -> SymbolReference.NodeReference(NodeIndex.FunctionLikeIndex(enclosingEntity, this))
                }

            private inline fun <D : FirCallableDeclaration, T : FirCallableSymbol<D>> Pair<T, FirQualifiedAccessExpression>.computeReference(
                crossinline toNodeReference: T.(EnclosingEntity<*>) -> SymbolReference<D>
            ): Unit = let { (symbol, access) ->
                // If the callable is an extension, ...
                if (symbol.isExtension) {
                    // Visit the extension receiver for dependencies
                    access.explicitReceiver?.collect()
                    access.extensionReceiver?.collect()
                }
                // Compute the reference to this callable based on the access' dispatch receiver
                val reference = access.dispatchReceiver?.let { receiver ->
                    when (receiver) {
                        is FirSuperReceiverExpression -> SymbolReference.MemberReference(symbol)
                        is FirThisReceiverExpression -> {
                            val boundSymbol = receiver.calleeReference.boundSymbol?.let { symbol ->
                                when (symbol) {
                                    is FirReceiverParameterSymbol -> symbol.containingDeclarationSymbol
                                    is FirTypeParameterSymbol -> null
                                    is FirTypeAliasSymbol -> symbol.fullyExpandedClass()
                                    else -> symbol
                                }
                            } ?: return@let SymbolReference.CapturedReference(symbol)
                            boundSymbol.asEntity(allowClass = false)
                                ?.let { symbol.toNodeReference(it) }
                                ?: SymbolReference.MemberReference(symbol)
                        }
                        is FirResolvedQualifier -> receiver.toEnclosingEntity()?.let { symbol.toNodeReference(it) }
                        is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                            ?.let { symbol.toNodeReference(it) }
                            ?: SymbolReference.CapturedReference(symbol)
                        else -> SymbolReference.CapturedReference(symbol)
                    }
                } ?: symbol.containingFileSymbol?.asFileEntity()?.let { symbol.toNodeReference(it) }
                ?: SymbolReference.CapturedReference(symbol)
                // Add the reference under the currently visiting symbol, if present
                addReferenceToCurrent(
                    reference = reference,
                    at = access
                )
                if (!reference.isStatic) {
                    // We "fallthrough" to the property declaration to look for dependencies
                    access.dispatchReceiver?.collect()
                    symbol.fir.collect()
                }
            }

            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression): Unit =
                propertyAccessExpression.visit {
                    // Case 1: Accessing an enum entry
                    calleeReference.toResolvedEnumEntrySymbol(discardErrorReference = true)?.asEnumEntryEntity()?.let { enumEntry ->
                        addReferenceToCurrent(
                            reference = SymbolReference.SubgraphReference(enumEntry),
                            at = propertyAccessExpression
                        )
                    }
                    // Case 2: Accessing a property
                    calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { symbol ->
                        (symbol to this).computeReference { toNodeReference(it) }
                    }
                }

            fun FirClassSymbol<*>.collectSuperTypeReferences(visited: MutableSet<FirClassSymbol<*>> = mutableSetOf(), at: FirExpression) {
                resolvedSuperTypes.forEach { superType ->
                    superType.fullyExpandedType().toRegularClassSymbol()?.let { superTypeSymbol ->
                        // Skip library supertypes, as they cannot have mutual dependencies with the source types, interface types without
                        // default methods, and types which are declared outside the current module
                        if (superTypeSymbol.isLibraryDeclaration
                            || !superTypeSymbol.isInitializedByItsSupertypes
                            || superTypeSymbol.moduleData.name != moduleName
                            || !visited.add(this)
                        ) return@let
                        // If the supertype has static declarations (it must be an enclosing entity), ...
                        superTypeSymbol.asClassEntity()?.let {
                            addReferenceToCurrent(
                                reference = SymbolReference.ClassReference(it),
                                at = at
                            )
                        } ?:
                        // Otherwise, the supertype's supertypes may have static declarations, so connect to them instead
                        superTypeSymbol.collectSuperTypeReferences(visited, at)
                    }
                }
            }

            override fun visitResolvedQualifier(resolvedQualifier: FirResolvedQualifier): Unit =
                resolvedQualifier.visit {
                    symbol?.fullyExpandedClass(session)?.apply {
                        // If the qualified class can be a value, ...
                        // Only objects can be used as values, enum entries are accessible as properties (variables), and (static) classes are not accessible
                        if (canBeValue) {
                            // Case 1: A companion object
                            if (resolvedToCompanionObject) {
                                resolvedCompanionObjectSymbol?.asObjectEntity(asClassEntity())?.let {
                                    addReferenceToCurrent(
                                        reference = SymbolReference.SubgraphReference(it),
                                        at = resolvedQualifier
                                    )
                                }
                            }
                            // Case 2: An object
                            else if (classKind.isObject) {
                                asObjectEntity()?.let {
                                    addReferenceToCurrent(
                                        reference = SymbolReference.SubgraphReference(it),
                                        at = resolvedQualifier
                                    )
                                }
                            }
                        }
                        // Otherwise, we at least try to ensure that the class is lazily initialized due to this reference
                        else {
                            asEntity()?.let { classEntity ->
                                when (classEntity) {
                                    is EnclosingEntity.Class -> addReferenceToCurrent(
                                        reference = SymbolReference.ClassReference(classEntity),
                                        at = resolvedQualifier
                                    )
                                    is EnclosingEntity.Object -> addReferenceToCurrent(
                                        reference = SymbolReference.ClassReference(classEntity),
                                        at = resolvedQualifier
                                    )
                                    else -> {}
                                }
                            } ?: collectSuperTypeReferences(at = resolvedQualifier)
                        }
                    }
                }

            override fun visitFunctionCall(functionCall: FirFunctionCall): Unit = functionCall.visit {
                // Check dependencies from the arguments
                argumentList.collect()
                functionCall.calleeReference.toResolvedFunctionSymbol(discardErrorReference = true)?.let { symbol ->
                    (symbol to this).computeReference { SymbolReference.NodeReference(NodeIndex.FunctionLikeIndex(it, this)) }
                }
            }

            override fun visitArgumentList(argumentList: FirArgumentList) = argumentList.visit { collectRecursively() }

            override fun visitCallableReferenceAccess(callableReferenceAccess: FirCallableReferenceAccess) {
                // Skip unused references
                if (elementStack.topOrNull() is FirBlock) return
                callableReferenceAccess.visit {
                    // References to enum entries, local properties, and value parameters are not supported; local function references are allowed
                    calleeReference.toResolvedFunctionSymbol(discardErrorReference = true)?.let { symbol ->
                        (symbol to this).computeReference { SymbolReference.NodeReference(NodeIndex.FunctionLikeIndex(it, this)) }
                    }
                    calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { symbol ->
                        (symbol to this).computeReference { toNodeReference(it) }
                    }
                }
            }

            override fun visitDelegatedConstructorCall(delegatedConstructorCall: FirDelegatedConstructorCall): Unit =
                delegatedConstructorCall.visit {
                    calleeReference.toResolvedConstructorSymbol(discardErrorReference = true)?.let {
                        // Skip constructor calls of library declarations, as they cannot create any dependencies
                        if (it.isLibraryDeclaration) return@visit
                        argumentList.collect()
                        it.fir.collect()
                    }
                }

            override fun visitQualifiedAccessExpression(qualifiedAccessExpression: FirQualifiedAccessExpression) = Unit

            /**
             * =============================================
             *         Visiting other expressions
             * =============================================
             */

            override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression): Unit =
                anonymousObjectExpression.visit { collectRecursively() }

            override fun visitBooleanOperatorExpression(booleanOperatorExpression: FirBooleanOperatorExpression): Unit =
                booleanOperatorExpression.visit { collectRecursively() }

            override fun visitCheckedSafeCallSubject(checkedSafeCallSubject: FirCheckedSafeCallSubject): Unit =
                checkedSafeCallSubject.visit { originalReceiverRef.value.collect() }

            override fun visitCheckNotNullCall(checkNotNullCall: FirCheckNotNullCall): Unit =
                checkNotNullCall.visit { collectRecursively() }

            override fun visitClassReferenceExpression(classReferenceExpression: FirClassReferenceExpression): Unit =
                classReferenceExpression.visit {
                    classTypeRef.toRegularClassSymbol(session)?.let { symbol ->
                        symbol.asEntity()?.let { classEntity ->
                            when (classEntity) {
                                is EnclosingEntity.Class ->
                                    addReferenceToCurrent(SymbolReference.ClassReference(classEntity), classReferenceExpression)
                                is EnclosingEntity.Object ->
                                    addReferenceToCurrent(SymbolReference.ClassReference(classEntity), classReferenceExpression)
                                else -> {}
                            }
                        } ?: symbol.collectSuperTypeReferences(at = classReferenceExpression)
                    }
                }

            override fun visitCollectionLiteral(collectionLiteral: FirCollectionLiteral): Unit =
                collectionLiteral.visit { collectRecursively() }

            override fun visitComparisonExpression(comparisonExpression: FirComparisonExpression): Unit =
                comparisonExpression.visit { collectRecursively() }

            override fun visitComponentCall(componentCall: FirComponentCall): Unit = visitFunctionCall(componentCall)

            override fun visitDesugaredAssignmentValueReferenceExpression(
                desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression
            ): Unit = desugaredAssignmentValueReferenceExpression.visit { expressionRef.value.collect() }

            override fun visitElvisExpression(elvisExpression: FirElvisExpression): Unit =
                elvisExpression.visit { collectRecursively() }

            override fun visitEnumEntryDeserializedAccessExpression(
                enumEntryDeserializedAccessExpression: FirEnumEntryDeserializedAccessExpression
            ): Unit = enumEntryDeserializedAccessExpression.visit {
                enumClassId.toLookupTag().toSymbol()
                    ?.fullyExpandedClass()
                    ?.collectEnumEntries()
                    ?.find { it.name == enumEntryDeserializedAccessExpression.enumEntryName }
                    ?.asEnumEntryEntity()
                    ?.let { enumEntry ->
                        addReferenceToCurrent(SymbolReference.SubgraphReference(enumEntry), enumEntryDeserializedAccessExpression)
                    }
            }

            override fun visitEqualityOperatorCall(equalityOperatorCall: FirEqualityOperatorCall): Unit = equalityOperatorCall.visit {
                collectRecursively()
            }

            override fun visitGetClassCall(getClassCall: FirGetClassCall): Unit = getClassCall.visit { argument.collect() }

            override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall): Unit =
                visitFunctionCall(implicitInvokeCall)

            override fun visitIncrementDecrementExpression(incrementDecrementExpression: FirIncrementDecrementExpression): Unit =
                incrementDecrementExpression.visit { collectRecursively() }

            override fun visitIntegerLiteralOperatorCall(integerLiteralOperatorCall: FirIntegerLiteralOperatorCall): Unit =
                visitFunctionCall(integerLiteralOperatorCall)

            override fun visitMultiDelegatedConstructorCall(
                multiDelegatedConstructorCall: FirMultiDelegatedConstructorCall
            ): Unit = Unit

            override fun visitNamedArgumentExpression(namedArgumentExpression: FirNamedArgumentExpression): Unit =
                namedArgumentExpression.visit { collectRecursively() }

            override fun visitReturnExpression(returnExpression: FirReturnExpression): Unit = returnExpression.visit {
                collectRecursively()
            }

            override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression): Unit = safeCallExpression.visit {
                collectRecursively()
            }

            override fun visitSmartCastExpression(smartCastExpression: FirSmartCastExpression): Unit =
                smartCastExpression.visit { collectRecursively() }

            override fun visitSpreadArgumentExpression(spreadArgumentExpression: FirSpreadArgumentExpression): Unit =
                spreadArgumentExpression.visit { acceptChildren(this@SymbolReferenceCollector) }

            override fun visitStringConcatenationCall(stringConcatenationCall: FirStringConcatenationCall): Unit =
                stringConcatenationCall.visit { acceptChildren(this@SymbolReferenceCollector) }

            override fun visitThrowExpression(throwExpression: FirThrowExpression): Unit = throwExpression.visit {
                acceptChildren(this@SymbolReferenceCollector)
            }

            override fun visitTryExpression(tryExpression: FirTryExpression): Unit = tryExpression.visit {
                acceptChildren(this@SymbolReferenceCollector)
            }

            override fun visitWhenExpression(whenExpression: FirWhenExpression): Unit = whenExpression.visit {
                acceptChildren(this@SymbolReferenceCollector)
            }

            override fun visitWhenSubjectExpression(whenSubjectExpression: FirWhenSubjectExpression): Unit = whenSubjectExpression.visit {
                acceptChildren(this@SymbolReferenceCollector)
            }

            override fun visitWrappedArgumentExpression(wrappedArgumentExpression: FirWrappedArgumentExpression): Unit =
                wrappedArgumentExpression.visit { acceptChildren(this@SymbolReferenceCollector) }

            override fun visitWrappedDelegateExpression(wrappedDelegateExpression: FirWrappedDelegateExpression): Unit =
                wrappedDelegateExpression.visit { acceptChildren(this@SymbolReferenceCollector) }

            override fun visitWrappedExpression(wrappedExpression: FirWrappedExpression): Unit = wrappedExpression.visit {
                acceptChildren(this@SymbolReferenceCollector)
            }
        }

        private val symbolReferenceCollector = SymbolReferenceCollector()

        private data class ConstructionContext(
            val enclosingEntity: EnclosingEntity<*>,
            var lastConstructedNode: NodeIndex<*>? = null
        )

        private val contexts: Stack<ConstructionContext> = stackOf()
        private val startedInitializing: MutableSet<EnclosingEntity<*>> = mutableSetOf()
        private val dirtyNodes: MutableSet<NodeIndex<*>> = mutableSetOf()

        private val context: ConstructionContext? get() = contexts.topOrNull()

        private val lastConstructedNode: NodeIndex<*>? get() = context?.lastConstructedNode

        private fun NodeIndex<*>?.accesses(other: NodeIndex<*>, at: Set<FirExpression>): Boolean =
            context(graph) {
                (this accesses (other to at)).ifTrue {
                    this?.let { dirtyNodes.add(it) }
                    dirtyNodes.add(other)
                    true
                } ?: false
            }

        private fun NodeIndex<*>?.happensBefore(other: NodeIndex<*>): Boolean =
            context(graph) {
                (this happensBefore other).ifTrue {
                    this?.let { dirtyNodes.add(it) }
                    dirtyNodes.add(other)
                    true
                } ?: false
            }

        private fun NodeIndex<*>?.mayHappenBefore(other: NodeIndex<*>): Boolean =
            context(graph) {
                (this mayHappenBefore other).ifTrue {
                    this?.let { dirtyNodes.add(it) }
                    dirtyNodes.add(other)
                    true
                } ?: false
            }

        private inline fun buildSubgraph(
            enclosingEntity: EnclosingEntity<*>,
            rebuild: Boolean = false,
            init: ConstructionContext.() -> Unit
        ) {
            // If the entity has previously started constructing, skip it
            if (!startedInitializing.add(enclosingEntity) && !rebuild) return

            // Create a new context for the enclosing entity and initialize it
            val newContext = ConstructionContext(enclosingEntity)
            var success = false
            contexts.push(newContext)
            try {
                newContext.init()
                success = true
            } catch (e: Throwable) {
                session.exceptionHandler.handleExceptionOnElementAnalysis(enclosingEntity.symbol.fir, e)
            } finally {
                contexts.pop()
                // If the context is nested directly under the parent's context, connect the begin node to the last constructed node,
                // and continue construction from the end node
                if (success && context?.enclosingEntity?.let { it == newContext.enclosingEntity.parentEnclosingEntity } ?: false) {
                    context?.let {
                        it.lastConstructedNode.happensBefore(enclosingEntity.beginSubgraphIndex)
                        it.lastConstructedNode = enclosingEntity.endSubgraphIndex
                    }
                }
            }
        }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>> getOrCreateNode(
            index: NodeIndex<D>,
            enclosingEntity: EnclosingEntity<*>,
            crossinline new: () -> T,
        ): DependencyNode<*> = graph[index] ?: new().apply {
            // Store the node under its index and its entity
            graph.nodes[index] = this
            graph.entities.getOrPut(enclosingEntity) { linkedSetOf() }.add(index)
            // Mark the new node dirty
            dirtyNodes.add(index)
        }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>> buildNode(
            index: NodeIndex<D>,
            enclosingEntity: EnclosingEntity<*>,
            crossinline new: () -> T,
            crossinline init: (DependencyNode<*>) -> Unit,
        ): DependencyNode<*> {
            require(context?.enclosingEntity == enclosingEntity) { "The node must be built in the context of its enclosing entity!" }
            return getOrCreateNode(index, enclosingEntity, new).apply {
                // Maintain the happens-before subgraph
                lastConstructedNode.happensBefore(index)
                context?.lastConstructedNode = index
                init(this)
            }
        }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>> lazyBuildNode(
            index: NodeIndex<D>,
            enclosingEntity: EnclosingEntity<*>,
            crossinline new: () -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean,
        ): DependencyNode<*> = getOrCreateNode(index, enclosingEntity, new).apply {
            // Maintain the may-happen-before dependency due to lazy initialization
            lastConstructedNode?.let { index.connect(it) }
        }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>> lazyAccessNode(
            index: NodeIndex<D>,
            enclosingEntity: EnclosingEntity<*>,
            at: Set<FirExpression>,
            crossinline new: () -> T,
        ): DependencyNode<*> = getOrCreateNode(index, enclosingEntity, new).apply {
            // Maintain the information flow outgoing from the constructed node
            lastConstructedNode.accesses(index, at)
        }

        private inline fun <D : FirDeclaration> buildBeginInitializationNode(
            enclosingEntity: EnclosingEntity<D>,
            crossinline init: (DependencyNode<*>) -> Unit = {}
        ): DependencyNode<*> = buildNode(
            index = enclosingEntity.beginSubgraphIndex,
            enclosingEntity = enclosingEntity,
            new = { DependencyNode.BeginInitializationNode(enclosingEntity) },
            init = init
        )

        private fun <D : FirDeclaration> lazyBuildBeginInitializationNode(enclosingEntity: EnclosingEntity<D>): DependencyNode<*> =
            lazyBuildNode(
                index = enclosingEntity.beginSubgraphIndex,
                enclosingEntity = enclosingEntity,
                new = { DependencyNode.BeginInitializationNode(enclosingEntity) },
                connect = { _ -> false }
            )

        private fun <D : FirDeclaration> lazyAccessBeginInitializationNode(
            enclosingEntity: EnclosingEntity<D>,
            at: Set<FirExpression>
        ): DependencyNode<*> = lazyAccessNode(
            index = enclosingEntity.beginSubgraphIndex,
            enclosingEntity = enclosingEntity,
            at = at,
            new = { DependencyNode.BeginInitializationNode(enclosingEntity) }
        )

        private inline fun <D : FirDeclaration> buildEndInitializationNode(
            enclosingEntity: EnclosingEntity<D>,
            crossinline init: (DependencyNode<*>) -> Unit = {}
        ): DependencyNode<*> = buildNode(
            index = enclosingEntity.endSubgraphIndex,
            enclosingEntity = enclosingEntity,
            new = { DependencyNode.EndInitializationNode(enclosingEntity) },
            init = init
        )

        private inline fun <D : FirDeclaration> lazyBuildEndInitializationNode(
            enclosingEntity: EnclosingEntity<D>,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { mayHappenBefore(it) }
        ): DependencyNode<*> = lazyBuildNode(
            index = enclosingEntity.endSubgraphIndex,
            enclosingEntity = enclosingEntity,
            new = { DependencyNode.EndInitializationNode(enclosingEntity) },
            connect = connect,
        )

        private inline fun <D : FirDeclaration> buildDeclarationNode(
            index: NodeIndex.DeclarationIndex<D>,
            crossinline init: (DependencyNode<*>) -> Unit
        ): DependencyNode<*> = buildNode(
            index = index,
            enclosingEntity = index.enclosingEntity,
            new = { DependencyNode.DeclarationNode(index) },
            init = init
        )

        private fun <D : FirDeclaration> lazyBuildDeclarationNode(index: NodeIndex.DeclarationIndex<D>): DependencyNode<*> =
            lazyBuildNode(
                index = index,
                enclosingEntity = index.enclosingEntity,
                new = { DependencyNode.DeclarationNode(index) },
                connect = { _ -> false },
            )

        private fun <D : FirDeclaration> lazyAccessDeclarationNode(
            index: NodeIndex.DeclarationIndex<D>,
            at: Set<FirExpression>
        ): DependencyNode<*> = lazyAccessNode(
            index = index,
            enclosingEntity = index.enclosingEntity,
            at = at,
            new = { DependencyNode.DeclarationNode(index) }
        )

        /**
         * Connects the subgraph of this entity (at its begin node) with an incoming happens-before edge to the subgraphs of its supertypes
         *
         * The supertypes connected to this entity are those which are directly initialized due to initialization of this entity, i.e.,
         * classes and interfaces with default methods. In cases where the supertype has no static declarations, we recurse into its supertypes
         * to and connect to those instead
         */
        private fun FirClassSymbol<*>.connectSubgraphToDirectlyInitializedSupertypes(
            visited: MutableSet<FirClassSymbol<*>> = mutableSetOf(this)
        ) {
            resolvedSuperTypes.forEach { superType ->
                superType.fullyExpandedType().toRegularClassSymbol()?.let { superTypeSymbol ->
                    // Skip library supertypes, as they cannot have mutual dependencies with the source types, interface types without
                    // default methods, and types which are declared outside the current module
                    if (superTypeSymbol.isLibraryDeclaration
                        || !superTypeSymbol.isInitializedByItsSupertypes
                        || superTypeSymbol.moduleData.name != moduleName
                        || !visited.add(superTypeSymbol)
                    ) return@let
                    // If the supertype has static declarations (it must be an enclosing entity), ...
                    superTypeSymbol.asClassEntity()?.let { enclosingEntity ->
                        // DO NOT VISIT THE SUPERTYPE AS IT WAS EITHER ALREADY VISITED OR IT WILL BE VISITED LATER
                        lazyBuildEndInitializationNode(
                            enclosingEntity = enclosingEntity,
                            connect = { happensBefore(it) }
                        )
                    } ?:
                    // Otherwise, the supertype's supertypes may have static declarations, so connect to them instead
                    superTypeSymbol.connectSubgraphToDirectlyInitializedSupertypes(visited)
                }
            }
        }

        /**
         * Retrieves all declarations that are initialized in order of declaration and in the order of initialization of the given class'
         * supertypes.
         *
         * For the sake of optimization, the resulting sequence excludes all library declarations that have been overridden by the given
         * class (or transitively by its supertypes)
         */
        @OptIn(ExplicitlyPassedSession::class, ScopeFunctionRequiresPrewarm::class)
        private fun FirClassSymbol<*>.getAllOrderedDeclarations(visited: MutableSet<FirClassSymbol<*>> = mutableSetOf()): Sequence<FirDeclaration> =
            sequence {
                // Prevent visiting the same class multiple times (in case multiple supertypes inherit from the same type)
                if (!visited.add(this@getAllOrderedDeclarations)) return@sequence
                // We require the use-site scope to provide us with all possible intersections and substitution overrides
                val useSiteScope = unsubstitutedScope(
                    session,
                    scopeSession,
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
                declarationSymbols.forEach { symbol ->
                    when (symbol) {
                        // For all properties...
                        is FirPropertySymbol if (!symbol.isLibraryDeclaration || symbol.visibility == Visibilities.Public) -> {
                            if (symbol.fromPrimaryConstructor) {
                                // If they are declared in the primary constructor, add them to the primary constructor declarations
                                fromPrimaryConstructor += symbol
                            } else if (!classKind.isInterface || classKind.isInterface && symbol.hasAnyImplementation) {
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
                // We do not need to enumerate the supertype hierarchy recursively because it is equivalent as recursively calling the cache
                // on each directly implemented supertype
                val superTypes = resolvedSuperTypes.mapNotNull {
                    it.fullyExpandedType(session).toRegularClassSymbol(session)
                }

                // Add declarations from the superclass (if exists), as it is always initialized first
                superTypes.find { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.ENUM_CLASS }?.let {
                    it.getAllOrderedDeclarations(visited).forEach { declaration ->
                        if (declaration.symbol !in overriddenLibraryCallables) yield(declaration)
                    }
                }

                // Add (default) declarations from the superinterfaces in order of declaration in the supertype specifiers
                // Interfaces without (declared or inherited) default methods will cache an empty set
                superTypes.filter { it.classKind == ClassKind.INTERFACE }.forEach {
                    it.getAllOrderedDeclarations(visited).forEach { declaration ->
                        if (declaration.symbol !in overriddenLibraryCallables) yield(declaration)
                    }
                }

                // This set will be empty for interfaces
                fromPrimaryConstructor.forEach { yield(it.fir) }
                // This will contain all default methods for interfaces
                fromBody.forEach { yield(it.fir) }
            }

        fun EnclosingEntity.InstancedProperty.unwrap() {
            // If the entity was not encountered before, so the graph has no begin node nor end node for this entity,
            // i.e., its parent must also be missing
            if (this !in graph) {
                // Recursively unwrap its parent
                (parentEnclosingEntity as? EnclosingEntity.InstancedProperty)?.unwrap()
            }
            // The entity was encountered before during construction of its parent entity (or it was unwrapped), i.e.,
            // begin node and end node may or may not be present. Hence, we construct the full subgraph anyway
            (correspondingClassSymbol as? FirRegularClassSymbol)?.let { classSymbol ->
                buildSubgraph(enclosingEntity = this, rebuild = true) {
                    buildBeginInitializationNode(this@unwrap) {
                        // OPTIMIZATION: do not connect subgraphs for entities of library types
                        if (!classSymbol.isLibraryDeclaration) {
                            classSymbol.asClassEntity()?.let { classEntity ->
                                lazyBuildEndInitializationNode(
                                    enclosingEntity = classEntity,
                                    connect = { happensBefore(it) }
                                )
                            } ?: classSymbol.connectSubgraphToDirectlyInitializedSupertypes()
                        }
                        // The initializer and getter dependencies are collected with the outer enclosing entity
                        AccessEvaluationContext(parentEnclosingEntity, it.index).collectAccessesFor(symbol)
                    }
                    classSymbol.getAllOrderedDeclarations().forEach {
                        when (it) {
                            is FirProperty -> it.buildProperty(enclosingEntity)
                            is FirAnonymousInitializer -> it.buildAnonymousInitializer(enclosingEntity)
                            else -> {}
                        }
                    }
                    buildEndInitializationNode(this@unwrap)
                }
            }
        }

        private fun FirBasedSymbol<*>.pendingResolution(): MutableSet<AccessEvaluationContext>? =
            session.firProvider.getContainingFile(if (this is FirAnonymousInitializerSymbol) containingDeclarationSymbol else this)
                ?.takeIf { it.symbol !in visitedFiles }
                ?.let { file ->
                    require(file.symbol in pendingResolution && pendingResolution.getValue(file.symbol).isNotEmpty()) {
                        "The file must have pending symbols for resolution, namely $this!"
                    }
                    val symbols = pendingResolution.getValue(file.symbol)
                    require(this in symbols && this !in symbolReferences) {
                        "The symbol must be traversed by the collector first and have no references!"
                    }
                    symbols.getValue(this)
                }

        private fun NodeIndex.FunctionLikeIndex<*>.lazyBuildFunctionLikeNode() {
            // (Re)build the subgraph for its enclosing entity while only constructing the function-like node
            buildSubgraph(enclosingEntity = enclosingEntity, rebuild = true) {
                // Build the function-like node and its dependencies
                buildDeclarationNode(this@lazyBuildFunctionLikeNode) {
                    AccessEvaluationContext(
                        enclosingEntity = enclosingEntity,
                        accessingNode = it.index
                    ).collectAccessesFor(symbol)
                }
                // Build the outermost end node lazily as we want to connect it to the function-like node with a may-happen-before edge
                lazyBuildEndInitializationNode(enclosingEntity.outermostEntity)
            }
        }

        private fun AccessEvaluationContext.lazyInitDependency(accessedEntity: EnclosingEntity<*>, inDynamicContext: Boolean = false) {
            // Resolve the may-happen-before edge due to access
            val accessedOutermostEntity = accessedEntity.outermostEntity
            // Only connect entities that are not directly nested under the same outermost entity
            if (enclosingEntity.outermostEntity != accessedOutermostEntity || inDynamicContext) {
                lazyBuildEndInitializationNode(
                    enclosingEntity = accessedOutermostEntity
                )
            }
        }

        private fun AccessEvaluationContext.collectAccessesFor(symbol: FirBasedSymbol<*>) {
            // When the symbol has not been collected yet (possibly because its unresolved)
            if (symbol !in symbolReferences) {
                symbol.fir.accept(symbolReferenceCollector)
                symbol.pendingResolution()?.let {
                    it.add(this)
                    return
                }
            }
            // Collect references if the symbol has not been visited yet
            if (!visited.add(symbol)) return
            symbolReferences[symbol]?.forEach { (reference, expressions) ->
                when (reference) {
                    is SymbolReference.NodeReference<*> -> {
                        // If the reference is to a function-like node, ...
                        if (reference.index is NodeIndex.FunctionLikeIndex) {
                            // And it is not present in the graph, ...
                            if (reference.index !in graph) reference.index.lazyBuildFunctionLikeNode()
                            // Access the function-like node
                            lazyAccessDeclarationNode(reference.index, expressions)
                            // Always connect accessed function-like nodes to the accessor nodes with happens-before edges,
                            // even if they are nested under the same outermost entity
                            lastConstructedNode?.let { reference.index.happensBefore(it) }
                        }
                        // Otherwise, simply access the declaration node (but unwrap its parent first)
                        else {
                            // Unwrap the parent enclosing entity if possible
                            (reference.index.enclosingEntity.parentEnclosingEntity as? EnclosingEntity.InstancedProperty)?.unwrap()
                            // Access the declaration node
                            lazyAccessDeclarationNode(
                                index = reference.index,
                                at = expressions
                            )
                            // Resolve the may-happen-before edge from the end node due to access
                            lazyInitDependency(reference.index.enclosingEntity, isDynamic)
                        }
                    }
                    is SymbolReference.SubgraphReference<*> -> {
                        // Unwrap the parent enclosing entity if possible
                        (reference.enclosingEntity.parentEnclosingEntity as? EnclosingEntity.InstancedProperty)?.unwrap()
                        // Access the begin node
                        lazyAccessBeginInitializationNode(
                            enclosingEntity = reference.enclosingEntity,
                            at = expressions
                        )
                        // Resolve the may-happen-before edge due to access
                        lazyInitDependency(reference.enclosingEntity, isDynamic)
                    }
                    is SymbolReference.ClassReference<*> -> lazyInitDependency(reference.enclosingEntity)
                    is SymbolReference.MemberReference<*> if !isDynamic ->
                        when (val symbol = reference.symbol) {
                            is FirPropertySymbol if symbol.hasInitializer -> {
                                // No need to unwrap as we would not be visiting the accesses for this entity if its parent was not unwrapped
                                if (symbol.resolvedReturnType.let { it.isPrimitiveOrNullablePrimitive || it.isUnit || it.isNothing }) {
                                    lazyAccessDeclarationNode(
                                        index = NodeIndex.PrimitivePropertyIndex(enclosingEntity, symbol),
                                        at = expressions
                                    )
                                } else {
                                    lazyAccessBeginInitializationNode(
                                        enclosingEntity = symbol.asInstancedPropertyEntity(enclosingEntity),
                                        at = expressions
                                    )
                                }
                                // DISCLAIMER: No may-happen-before edge here because we are accessing the same entity we are visiting
                            }
                            is FirPropertySymbol, is FirFunctionSymbol<*> -> {
                                val index = NodeIndex.FunctionLikeIndex(enclosingEntity, symbol)
                                // If the function-like node is not present in the graph, ...
                                if (index !in graph) index.lazyBuildFunctionLikeNode()
                                lazyAccessDeclarationNode(
                                    index = index,
                                    at = expressions
                                )
                                // even if they are nested under the same outermost entity
                                lastConstructedNode?.let { index.happensBefore(it) }
                            }
                            else -> {}
                        }
                    is SymbolReference.MemberReference, is SymbolReference.CapturedReference -> {
                        if (!isDynamic) {
                            AccessEvaluationContext(
                                enclosingEntity = enclosingEntity,
                                accessingNode = accessingNode,
                                visited = visited,
                                isDynamic = true
                            ).collectAccessesFor(reference.symbol)
                        } else {
                            collectAccessesFor(reference.symbol)
                        }
                    }
                }
            }
        }

        /**
         * Condenses the graph by removing multi-node strongly connected components and replacing them with composite nodes
         */
        private fun condenseGraph() {
            if (dirtyNodes.isEmpty()) return
            val queue = LinkedList<DependencyNode<*>>()

            // Collect all forward reachable nodes from the marked dirty nodes
            dirtyNodes.asSequence().mapNotNull(graph::get).forEach(queue::add)
            val forwardReachable = linkedSetOf<DependencyNode<*>>()
            while (queue.isNotEmpty()) {
                val first = queue.pop()
                if (forwardReachable.add(first)) {
                    first.possiblyHappenAfter.mapNotNull(graph::get).forEach(queue::add)
                }
            }

            // Collect all backwards reachable nodes from the marked dirty nodes
            dirtyNodes.asSequence().mapNotNull(graph::get).forEach(queue::add)
            val backwardReachable = linkedSetOf<DependencyNode<*>>()
            while (queue.isNotEmpty()) {
                val first = queue.pop()
                if (backwardReachable.add(first)) {
                    first.possiblyHappenBefore.mapNotNull(graph::get).forEach(queue::add)
                }
            }

            context(graph) {
                // Consider only nodes that are reachable from both directions (dirtyNodes are subsumed by this)
                // For each strong connected component of size > 1, condense it
                forwardReachable.intersect(backwardReachable)
                    .stronglyConnectedComponents()
                    .forEach { if (it.size > 1) it.condense() }
            }

            // Clear the dirty nodes
            dirtyNodes.clear()
        }

        /**
         * =============================================
         *          DEPENDENCY CONSTRUCTION
         * =============================================
         */

        fun addDependencies(file: FirFile) {
            println("Visiting file ${file.name} (node count: ${graph.nodes.size})")
            // Skip already visited files
            if (!visitedFiles.add(file.symbol)) return

            // Process symbols of the file that are pending resolution
            pendingResolution[file.symbol]?.let { symbols ->
                println("Found pending symbols for resolution for file ${file.name}")
                println(symbols)
                // Collect accesses for the newly resolved symbols
                symbols.forEach { (symbol, contexts) ->
                    contexts.forEach { context ->
                        buildSubgraph(enclosingEntity = context.enclosingEntity, rebuild = true) {
                            lastConstructedNode = context.accessingNode
                            // The first invocation for the symbol also caches its symbol references
                            context.collectAccessesFor(symbol)
                        }
                    }
                }
                // Remove the file from pending resolution
                pendingResolution.remove(file.symbol)
            }

            // Build subgraph for the file
            val enclosingEntity = file.symbol.asFileEntity()
            buildSubgraph(enclosingEntity) {
                buildBeginInitializationNode(enclosingEntity)
                // Keep track of which node has been previously constructed as properties and functions reside in different branches
                file.declarations.forEach { declaration ->
                    when (declaration) {
                        is FirProperty -> declaration.buildProperty(enclosingEntity)
                        // JVM initialization of the file's class does not initialize the classes declared inside it.
                        // Hence, they will not be connected to the file's happens-before subgraph
                        is FirRegularClass -> declaration.buildEntity()
                        else -> {}
                    }
                }
                buildEndInitializationNode(enclosingEntity) {}
            }
            // Condense the graph
            println("Finished visiting file ${file.name}, new node count: ${graph.nodes.size}")
            println(symbolReferences)
            condenseGraph()
            println("Node count after condensation: ${graph.nodes.size}")
            println(graph)
        }

        private fun FirRegularClass.buildEntity(outerEnclosingEntity: EnclosingEntity.Class? = null) {
            // Case 1: an object with or without inheritance
            if (classKind.isObject) {
                symbol.asObjectEntity(outerEnclosingEntity)?.let { enclosingEntity ->
                    buildSubgraph(enclosingEntity) {
                        buildBeginInitializationNode(enclosingEntity) {
                            symbol.connectSubgraphToDirectlyInitializedSupertypes()
                            symbol.primaryConstructorIfAny(session)?.let { constructor ->
                                AccessEvaluationContext(enclosingEntity, it.index).collectAccessesFor(constructor)
                            }
                        }
                        symbol.getAllOrderedDeclarations().forEach {
                            when (it) {
                                is FirProperty -> it.buildProperty(enclosingEntity)
                                is FirAnonymousInitializer -> it.buildAnonymousInitializer(enclosingEntity)
                                is FirRegularClass -> it.buildEntity()
                                else -> {}
                            }
                        }
                        buildEndInitializationNode(enclosingEntity)
                    }
                }
            }
            // Case 2: a class with static declarations, i.e., an enum class and/or a class with a companion object
            else if (classKind.isEnumClass || symbol.resolvedCompanionObjectSymbol != null) {
                symbol.asClassEntity()?.let { enclosingEntity ->
                    buildSubgraph(enclosingEntity) {
                        buildBeginInitializationNode(enclosingEntity) {
                            symbol.connectSubgraphToDirectlyInitializedSupertypes()
                            symbol.primaryConstructorIfAny(session)?.let { constructor ->
                                AccessEvaluationContext(enclosingEntity, it.index).collectAccessesFor(constructor)
                            }
                        }
                        processAllDeclarations(session) { symbol ->
                            when (val declaration = symbol.fir) {
                                is FirProperty if declaration.isGeneratedStaticEnumMember(this@buildEntity) ->
                                    declaration.buildProperty(enclosingEntity)
                                is FirEnumEntry -> declaration.buildEnumEntry(enclosingEntity)
                                // Only companion objects will connect to this happens-before subgraph
                                is FirRegularClass if declaration.classKind.isObject && declaration.isCompanion ->
                                    declaration.buildEntity(enclosingEntity)
                                is FirRegularClass -> declaration.buildEntity()
                                else -> {}
                            }
                        }
                        buildEndInitializationNode(enclosingEntity)
                    }
                }
            }
        }

        private fun FirProperty.buildProperty(outerEnclosingEntity: EnclosingEntity<*>) {
            println("Building property $symbol (hasInitializer: ${initializer != null})")
            if (!isLocal && isVal && initializer != null) {
                returnTypeRef.coneTypeOrNull?.let { type ->
                    // Case 1: A property of primitive type (possibly nullable)
                    if (type.isPrimitiveOrNullablePrimitive || type.isUnit || type.isNothing) {
                        buildDeclarationNode(NodeIndex.PrimitivePropertyIndex(outerEnclosingEntity, symbol)) {
                            AccessEvaluationContext(outerEnclosingEntity, it.index).collectAccessesFor(symbol)
                        }
                    }
                    // Case 2: A property of a class type (with a subgraph)
                    else {
                        type.toRegularClassSymbol()?.let { classSymbol ->
                            // Skip properties which are not library declaration and belong to another module
                            if (!classSymbol.isLibraryDeclaration && classSymbol.moduleData.name != moduleName) return
                            val enclosingEntity = symbol.asInstancedPropertyEntity(outerEnclosingEntity)
                            buildSubgraph(enclosingEntity) {
                                buildBeginInitializationNode(enclosingEntity) {
                                    // OPTIMIZATION: do not connect subgraphs for entities of library types
                                    if (!classSymbol.isLibraryDeclaration) {
                                        classSymbol.asClassEntity()?.let { classEntity ->
                                            lazyBuildEndInitializationNode(
                                                enclosingEntity = classEntity,
                                                connect = { happensBefore(it) }
                                            )
                                        } ?: classSymbol.connectSubgraphToDirectlyInitializedSupertypes()
                                    }
                                    // The initializer and getter dependencies are collected with the outer enclosing entity
                                    AccessEvaluationContext(outerEnclosingEntity, it.index).collectAccessesFor(symbol)
                                }
                                // Don't build the body yet, as it should be unwrapped lazily on access
                                buildEndInitializationNode(enclosingEntity)
                            }
                        } ?: error("Skipping construction of $symbol in module $moduleName")
                    }
                }
            }
        }

        private fun FirEnumEntry.buildEnumEntry(outerEnclosingEntity: EnclosingEntity.Class) {
            val enclosingEntity = symbol.asEnumEntryEntity()
            require(enclosingEntity.parentEnclosingEntity == outerEnclosingEntity) {
                "The provided outer entity must match the enum entry's parent!"
            }
            symbol.initializerObjectSymbol?.let { symbol ->
                buildSubgraph(enclosingEntity) {
                    buildBeginInitializationNode(enclosingEntity) {
                        symbol.connectSubgraphToDirectlyInitializedSupertypes()
                        symbol.primaryConstructorIfAny(session)?.let { constructor ->
                            AccessEvaluationContext(enclosingEntity, it.index).collectAccessesFor(constructor)
                        }
                    }
                    symbol.getAllOrderedDeclarations().forEach {
                        when (it) {
                            is FirProperty -> it.buildProperty(enclosingEntity)
                            is FirAnonymousInitializer -> it.buildAnonymousInitializer(enclosingEntity)
                            // Ignore enum class' synthetic functions?
                            // NOTE: no classifiers should be accessible from an enum entry's anonymous object, as the enum entry
                            //  has the type of its (parent) enum class
                            else -> {}
                        }
                    }
                    buildEndInitializationNode(enclosingEntity)
                }
            }
        }

        private fun FirAnonymousInitializer.buildAnonymousInitializer(enclosingEntity: EnclosingEntity<*>) {
            buildDeclarationNode(NodeIndex.AnonymousInitializerIndex(enclosingEntity, symbol)) {
                AccessEvaluationContext(enclosingEntity, it.index).collectAccessesFor(symbol)
            }
        }
    }
}
