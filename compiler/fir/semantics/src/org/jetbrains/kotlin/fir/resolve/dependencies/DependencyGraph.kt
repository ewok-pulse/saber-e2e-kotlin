/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.isEnumClass
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.fullyExpandedClass
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.processAllClassifiers
import org.jetbrains.kotlin.fir.declarations.processAllDeclarations
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirAnonymousObjectExpression
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
import org.jetbrains.kotlin.fir.references.toResolvedConstructorSymbol
import org.jetbrains.kotlin.fir.references.toResolvedEnumEntrySymbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
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
import org.jetbrains.kotlin.fir.resolve.toClassLikeSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isEnum
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isPrimitiveOrNullablePrimitive
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.types.toRegularClassSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
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
            .map { it.outermostEntity }
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
                        when (node) {
                            is DependencyNode.PrimitivePropertyNode,
                            is DependencyNode.FunctionNode<*>,
                            is DependencyNode.QualifierNode,
                            is DependencyNode.EnumEntryNode,
                            is DependencyNode.CompositeNode,
                            is DependencyNode.InstancedPropertyNode
                                -> "circle"
                            is DependencyNode.AnonymousInitializerNode,
                            is DependencyNode.ClinitNode,
                            is DependencyNode.TopLevelNode,
                            is DependencyNode.EndInitializationNode<*>
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

        sealed class DeclarationNode<D : FirDeclaration> : SingletonNode<D>() {
            abstract override val index: NodeIndex.DeclarationIndex<D>
            override val enclosingEntity: EnclosingEntity<*> get() = index.enclosingEntity
        }

        sealed class BeginInitializationNode<D : FirDeclaration> : SingletonNode<D>() {
            abstract override val enclosingEntity: EnclosingEntity<D>
            override val index: NodeIndex.BeginSubgraphIndex<D> get() = enclosingEntity.beginSubgraphIndex
        }

        data class EndInitializationNode<D : FirDeclaration>(override val enclosingEntity: EnclosingEntity<D>) : SingletonNode<D>() {
            override val index: NodeIndex.EndSubgraphIndex<D> = enclosingEntity.endSubgraphIndex
        }

        /**
         * Represents access to a static property (i.e., to a top-level property, an object property, or an enum entry property)
         */
        data class PrimitivePropertyNode(override val index: NodeIndex.PrimitivePropertyIndex) : DeclarationNode<FirProperty>()

        data class AnonymousInitializerNode(override val index: NodeIndex.AnonymousInitializerIndex) :
            DeclarationNode<FirAnonymousInitializer>()

        data class FunctionNode<D : FirFunction>(override val index: NodeIndex.FunctionIndex<D>) : DeclarationNode<D>()

        data class QualifierNode(override val enclosingEntity: EnclosingEntity.Object) : BeginInitializationNode<FirRegularClass>()

        data class TopLevelNode(override val enclosingEntity: EnclosingEntity.File) : BeginInitializationNode<FirFile>()

        data class ClinitNode(override val enclosingEntity: EnclosingEntity.Class) : BeginInitializationNode<FirRegularClass>()

        data class EnumEntryNode(override val enclosingEntity: EnclosingEntity.EnumEntry) : BeginInitializationNode<FirEnumEntry>()

        data class InstancedPropertyNode(override val enclosingEntity: EnclosingEntity.InstancedProperty) :
            BeginInitializationNode<FirProperty>()

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
                            is SingletonNode<*> -> {
                                // For singleton nodes, we keep the mapping of their node indices to this condensed node,
                                // as we require their presence in the graph for further analysis of their accesses
                                graph.nodes[node.index] = this
//                                graph.entities[node.enclosingEntity]?.let { nodes ->
//                                    nodes.remove(node)
//                                    nodes.add(this)
//                                }
                            }
                            is CompositeNode -> {
                                // For composite nodes, they are only preserved through time dependencies, so once the node
                                // is detached, it has no accesses by itself and can be safely removed from the graph
                                graph.nodes.remove(node.index)
//                                node.enclosingEntities.asSequence()
//                                    .mapNotNull(graph.entities::get)
//                                    .forEach { nodes ->
//                                        nodes.remove(node)
//                                        nodes.add(this)
//                                    }
                            }
                        }
                    }
                }
            }
        }

        companion object {

            context(graph: DependencyGraph)
            infix fun NodeIndex<*>?.accesses(access: Pair<NodeIndex<*>, FirExpression>): Boolean {
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
        val graph: DependencyGraph = session.dependencyGraph,
    ) : FirVisitorVoid(), SessionAndScopeSessionHolder {

        private sealed interface SubgraphScope {
            val visitingEntity: EnclosingEntity<*>
            val lastConstructedNode: NodeIndex<*>
            val firstUses: MutableMap<NodeIndex<*>, NodeIndex<*>>
            val isStatic: Boolean

            data class StaticScope(
                override val visitingEntity: EnclosingEntity<*>,
                override var lastConstructedNode: NodeIndex<*>,
                override val firstUses: MutableMap<NodeIndex<*>, NodeIndex<*>> = mutableMapOf(),
            ) : SubgraphScope {
                override val isStatic: Boolean = true
            }

            data class DynamicScope(
                override val visitingEntity: EnclosingEntity<*>,
                override val lastConstructedNode: NodeIndex<*>,
                override val firstUses: MutableMap<NodeIndex<*>, NodeIndex<*>> = mutableMapOf(),
            ) : SubgraphScope {
                override val isStatic: Boolean = false
            }
        }

        private val scopes: Stack<SubgraphScope> = stackOf()
        private val visiting: Stack<FirElement> = stackOf()
        private val startedInitializing: MutableSet<EnclosingEntity<*>> = mutableSetOf()
        private val dirtyNodes: MutableSet<NodeIndex<*>> = mutableSetOf()

        private inline fun <E : FirElement> E.visit(
            crossinline block: E.() -> Unit
        ) {
            visiting.push(this)
            try {
                block(this)
            } finally {
                visiting.pop()
            }
        }

        private val scope: SubgraphScope? get() = scopes.topOrNull()

        private val visitingEntity: EnclosingEntity<*>? get() = scope?.visitingEntity

        private val lastConstructedNode: NodeIndex<*>? get() = scope?.lastConstructedNode

        private val firstUses: MutableMap<NodeIndex<*>, NodeIndex<*>>? get() = scope?.firstUses

        private fun NodeIndex<*>?.accesses(other: NodeIndex<*>, at: FirExpression): Boolean =
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

        private fun <E : EnclosingEntity<*>> E.markFirstUse(allowInnerAccess: Boolean = false): E = apply {
            visitingEntity?.let { visitingEntity ->
                // Disallow marking of first uses of an entity due to an access from its outer entities
                val outermostEntity = outermostEntity
                if (visitingEntity.outermostEntity != outermostEntity || allowInnerAccess) {
                    scope?.let { scope ->
                        val index = outermostEntity.endSubgraphIndex
                        if (index !in scope.firstUses) {
                            buildEndInitializationNode(
                                enclosingEntity = outermostEntity,
                                initialize = false
                            )
                            index.mayHappenBefore(scope.lastConstructedNode)
                            scope.firstUses[index] = scope.lastConstructedNode
                        }
                    }
                }
            }
        }

        private fun DependencyNode<*>.markFirstUse(): DependencyNode<*> = apply {
            lastConstructedNode?.let { lastConstructedNode ->
                firstUses?.let { firstUses ->
                    if (index !in firstUses) {
                        index.happensBefore(lastConstructedNode)
                        firstUses[index] = lastConstructedNode
                    }
                }
            }
        }

        private val FirClassSymbol<*>.inheritancePropagatedDeclarations: InheritancePropagatedDeclarations
            get() = session.propagatedDeclarationsStorage.propagatedDeclarations.getValue(this, this@Builder)

        private val FirClass.inheritancePropagatedDeclarations: InheritancePropagatedDeclarations
            get() = symbol.inheritancePropagatedDeclarations

        private fun FirResolvedQualifier.toEnclosingEntity(): EnclosingEntity<FirRegularClass>? = symbol?.let { symbol ->
            symbol.fullyExpandedClass(symbol.moduleData.session)?.let {
                if (resolvedToCompanionObject && canBeValue) {
                    it.resolvedCompanionObjectSymbol?.asObjectEntity(it.asClassEntity())
                } else if (it.classKind.isObject && canBeValue) {
                    it.asObjectEntity()
                } else {
                    it.asClassEntity()
                }
            }
        }

        private fun FirPropertyAccessExpression.toEnclosingEntity(): EnclosingEntity<*>? =
            calleeReference.toResolvedEnumEntrySymbol(discardErrorReference = true)?.asEnumEntryEntity()
                ?: calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { propertySymbol ->
                    // If accessing local properties, add the dependencies from its children,
                    // as the builder will not visit the declaration at all
                    if (propertySymbol.isLocal) {
                        // What if I encounter another local property access in one of its blocks that is also local?
                        propertySymbol.fir.acceptChildren(this@Builder)
                        return@let null
                    }
                    // There can be no enclosing entity corresponding to a primitive property
                    if (propertySymbol.resolvedReturnType.let { it.isPrimitiveOrNullablePrimitive || it.isUnit || it.isNothing }) return@let null
                    val enclosingEntity = (dispatchReceiver ?: extensionReceiver)?.let { receiver ->
                        when (receiver) {
                            is FirSuperReceiverExpression, is FirThisReceiverExpression -> scope?.isStatic?.ifTrue {
                                visitingEntity?.correspondingClassSymbol
                                    ?.inheritancePropagatedDeclarations
                                    ?.let { propertySymbol in it }
                                    ?.ifTrue { visitingEntity }
                            }
                            is FirResolvedQualifier -> receiver.toEnclosingEntity()
                            is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                            else -> null
                        }
                    } ?: propertySymbol.containingFileSymbol?.asFileEntity()
                    enclosingEntity?.let { propertySymbol.asInstancedPropertyEntity(it) }
                }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>> buildNode(
            index: NodeIndex<D>,
            enclosingEntity: EnclosingEntity<*>,
            crossinline new: () -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean,
            noinline init: ((DependencyNode<*>) -> Unit)? = null
        ): DependencyNode<*> =
            (graph[index] ?: new().apply {
                // Store the node under its index and its entity
                graph.nodes[index] = this
                graph.entities.getOrPut(enclosingEntity) { linkedSetOf() }.add(index)
                // Mark the new node dirty
                dirtyNodes.add(index)
            }).apply {
                // Connect the node to the top of the stack
                lastConstructedNode.connect(index)
                // Initialize the node iff it is being accessed/constructed whilst visiting the subgraph of its enclosing entity
                // NOTE: this happens AT MOST once during graph construction, i.e., when visiting the enclosing entity's declaration
                init?.invoke(this)
            }

        private inline fun <D : FirDeclaration, E : EnclosingEntity<D>, T : DependencyNode.BeginInitializationNode<D>> buildBeginInitializationNode(
            enclosingEntity: E,
            crossinline new: (E) -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
            crossinline init: (DependencyNode<*>) -> Unit,
        ): DependencyNode<*> = enclosingEntity.beginSubgraphIndex.let { index ->
            buildNode(
                index = index,
                enclosingEntity = enclosingEntity,
                new = { new(enclosingEntity) },
                connect = connect,
                init = {
                    // Push the new subgraph scope of the enclosing entity onto the stack
                    val scope = SubgraphScope.StaticScope(
                        visitingEntity = enclosingEntity,
                        lastConstructedNode = index,
                        firstUses = scope?.takeIf { scope ->
                            scope.visitingEntity == enclosingEntity.parentEnclosingEntity
                        }?.firstUses ?: mutableMapOf()
                    )
                    scopes.push(scope)
                    // Initialize the node
                    init(it)
                }
            )
        }

        private inline fun <D : FirDeclaration, E : EnclosingEntity<D>, T : DependencyNode.BeginInitializationNode<D>> buildBeginInitializationNode(
            enclosingEntity: E,
            crossinline new: (E) -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
        ): DependencyNode<*> = buildNode(enclosingEntity.beginSubgraphIndex, enclosingEntity, { new(enclosingEntity) }, connect)

        private inline fun <D : FirDeclaration, E : EnclosingEntity<D>> buildEndInitializationNode(
            enclosingEntity: E,
            initialize: Boolean = true,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
        ): DependencyNode<*> =
            // We never request to build the end node when recursively visiting its entity's declaration since it is a dummy node.
            // It is fully initialized only at the end of the entity's subgraph
            when (initialize && visitingEntity == enclosingEntity) {
                true -> buildNode(
                    index = enclosingEntity.endSubgraphIndex,
                    enclosingEntity = enclosingEntity,
                    new = { DependencyNode.EndInitializationNode(enclosingEntity) },
                    connect = connect,
                    init = {
                        // Pop the subgraph scope of the enclosing entity from the stack, as it is now fully constructed
                        scopes.pop()
                    }
                )
                false -> buildNode(
                    index = enclosingEntity.endSubgraphIndex,
                    enclosingEntity = enclosingEntity,
                    new = { DependencyNode.EndInitializationNode(enclosingEntity) },
                    connect = connect
                )
            }

        private inline fun <D : FirDeclaration, T : DependencyNode<D>, I : NodeIndex.DeclarationIndex<D>> buildDeclarationNode(
            index: I,
            crossinline new: (I) -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
            crossinline init: (DependencyNode<*>) -> Unit,
        ): DependencyNode<*> = buildNode(
            index = index,
            enclosingEntity = index.enclosingEntity,
            new = { new(index) },
            connect = connect,
            init = {
                require(scope?.let(SubgraphScope::isStatic) ?: true)
                // Set the last constructed node to this one
                (scope as? SubgraphScope.StaticScope)?.lastConstructedNode = index
                // Initialize the node
                init(it)
            })

        private inline fun <D : FirDeclaration, T : DependencyNode<D>, I : NodeIndex.DeclarationIndex<D>> buildDeclarationNode(
            index: I,
            crossinline new: (I) -> T,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
        ): DependencyNode<*> = buildNode(index, index.enclosingEntity, { new(index) }, connect)

        private inline fun <D : FirFunction> buildFunctionNode(
            index: NodeIndex.FunctionIndex<D>,
            crossinline connect: NodeIndex<*>?.(NodeIndex<*>) -> Boolean = { _, _ -> false },
            crossinline init: (DependencyNode<*>) -> Unit,
        ): DependencyNode<*> = buildNode(
            index = index,
            enclosingEntity = index.enclosingEntity,
            new = { DependencyNode.FunctionNode(index) },
            connect = connect,
            init = {
                // Function nodes are build lazily
                scopes.push(SubgraphScope.StaticScope(visitingEntity = index.enclosingEntity, lastConstructedNode = index))
                init(it)
                scopes.pop()
            }
        )

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

        override fun visitElement(element: FirElement): Unit = Unit

        /**
         * =============================================
         *                  DECLARATIONS
         * =============================================
         */

        @OptIn(DirectDeclarationsAccess::class)
        override fun visitFile(file: FirFile): Unit = file.visit {
            val enclosingEntity = symbol.asFileEntity()
            if (!startedInitializing.add(enclosingEntity)) return@visit
            buildBeginInitializationNode(
                enclosingEntity = enclosingEntity,
                new = DependencyNode<FirFile>::TopLevelNode,
                init = {}
            )
            // Keep track of which node has been previously constructed as properties and functions reside in different branches
            file.declarations.forEach { declaration ->
                when (declaration) {
                    is FirProperty -> declaration.accept(this@Builder)
                    is FirRegularClass if declaration.visibility == Visibilities.Public ->
                        // It cannot be a companion object or an enum entry, hence it will not be connected to the file's last constructed node
                        declaration.accept(this@Builder)
                    else -> {}
                }
            }
            buildEndInitializationNode(enclosingEntity) { happensBefore(it) }
            // Condense the graph
            condenseGraph()
        }

        private fun EnclosingEntity<*>.visitSuperTypes() {
            correspondingClassSymbol?.resolvedSuperTypes?.forEach { superType ->
                superType.fullyExpandedType().toRegularClassSymbol()?.let { superTypeSymbol ->
                    // Skip library supertypes, as they cannot have mutual dependencies with the source types
                    if (superTypeSymbol.isLibraryDeclaration || !superTypeSymbol.inheritancePropagatedDeclarations.initializesWithSubtypes) return@let
                    superTypeSymbol.asClassEntity()?.let { enclosingEntity ->
                        // Skip directly nested entities as they are connected during construction in the order of declaration
                        if (parentEnclosingEntity == enclosingEntity) return@let
                        // Visit the entity iff it has not started initializing to prevent infinite recursion, e.g. in enum entries or
                        // companion objects that inherit the class they are enclosed in
                        if (enclosingEntity !in startedInitializing) superTypeSymbol.fir.accept(this@Builder)
                        // It is not known whether the super enclosing entity has static declarations, so to connect it to its descendant,
                        // we need to check if its graph was constructed, i.e., it has "started initializing"
                        // NOTE: we assume that there are no cycles in the inheritance hierarchy!!
                        if (enclosingEntity in startedInitializing) {
                            lastConstructedNode?.let {
                                // Build the end node if it does not exist, i.e., if subtypes are contained in the body of the supertype (sealed classes)
                                buildEndInitializationNode(
                                    enclosingEntity = enclosingEntity,
                                    initialize = false,
                                )
                                enclosingEntity.endSubgraphIndex.happensBefore(it)
                            }
                        } else {
                            enclosingEntity.visitSuperTypes()
                        }
                    }
                }
            }
        }

        override fun visitRegularClass(regularClass: FirRegularClass): Unit = regularClass.visit {
            // Case 1: an object with or without inheritance
            if (classKind.isObject) {
                symbol.asObjectEntity(visitingEntity as? EnclosingEntity.Class)?.let { enclosingEntity ->
                    if (!startedInitializing.add(enclosingEntity)) return@visit
                    buildBeginInitializationNode(
                        enclosingEntity = enclosingEntity,
                        new = DependencyNode<FirRegularClass>::QualifierNode,
                        connect = {
                            enclosingEntity.parentEnclosingEntity?.let { parent ->
                                require(parent == visitingEntity)
                                // Connect to the last constructed node of the outer entity if we are currently visiting it
                                happensBefore(it)
                            } ?: false
                        },
                        init = {
                            enclosingEntity.visitSuperTypes()
                            symbol.primaryConstructorIfAny(session)?.fir?.accept(this@Builder)
                        }
                    )
                    inheritancePropagatedDeclarations.orderedDeclarations.forEach { it.fir.accept(this@Builder) }
                    // Build the end node
                    buildEndInitializationNode(enclosingEntity) { happensBefore(it) }
                    // Ensure that the last constructed node points to the end node of this subgraph to
                    // maintain the correct happens-before relationship due to initialization order, and continue its initialization,
                    // otherwise condense the graph as this is the outermost entity
                    enclosingEntity.parentEnclosingEntity?.let {
                        require(it == visitingEntity)
                        (scope as? SubgraphScope.StaticScope)?.lastConstructedNode = enclosingEntity.endSubgraphIndex
                    } ?: condenseGraph()
                    // Visit nested classifiers
                    symbol.processAllClassifiers(session) {
                        it.toLookupTag().toClassLikeSymbol()?.resolvedStatus?.let { status ->
                            if (status.visibility == Visibilities.Public) it.fir.accept(this@Builder)
                        }
                    }
                }
            }
            // Case 2: a class with static declarations, i.e., an enum class and/or a class with a companion object
            else if (classKind.isEnumClass || symbol.resolvedCompanionObjectSymbol != null) {
                symbol.asClassEntity()?.let { enclosingEntity ->
                    if (!startedInitializing.add(enclosingEntity)) return@visit
                    buildBeginInitializationNode(
                        enclosingEntity = enclosingEntity,
                        new = DependencyNode<FirRegularClass>::ClinitNode,
                        init = {
                            enclosingEntity.visitSuperTypes()
                            symbol.primaryConstructorIfAny(session)?.fir?.accept(this@Builder)
                        }
                    )
                    processAllDeclarations(session) { declSymbol ->
                        when (val declaration = declSymbol.fir) {
                            is FirCallableDeclaration if declaration.isGeneratedStaticEnumMember(this) -> declaration.accept(this@Builder)
                            is FirEnumEntry -> declaration.accept(this@Builder)
                            is FirRegularClass if declaration.isCompanion -> declaration.accept(this@Builder)
                            // The classifier is either a public class or an object that is not a companion, either will not be connected
                            // to the last constructed node of this class
                            is FirRegularClass if declaration.visibility == Visibilities.Public -> declaration.accept(this@Builder)
                            // What about synthetic valuesOf or entries for enum classes?
                            else -> {}
                        }
                    }
                    buildEndInitializationNode(enclosingEntity) { happensBefore(it) }
                    // Condense the graph
                    condenseGraph()
                }
            }
        }

        override fun visitEnumEntry(enumEntry: FirEnumEntry): Unit = enumEntry.visit {
            // Recursively visit the anonymous object declaration in its initializer
            acceptChildren(this@Builder)
        }

        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject): Unit =
            anonymousObject.visit {
                symbol.asEnumEntryEntity()?.let { enclosingEntity ->
                    if (!startedInitializing.add(enclosingEntity)) return@visit
                    // Invariant: the currently visiting entity is the parent of the enum entry
                    require(enclosingEntity.parentEnclosingEntity == visitingEntity)
                    buildBeginInitializationNode(
                        enclosingEntity = enclosingEntity,
                        new = DependencyNode<FirEnumEntry>::EnumEntryNode,
                        connect = { happensBefore(it) },
                        init = {
                            enclosingEntity.visitSuperTypes()
                            symbol.primaryConstructorIfAny(session)?.fir?.accept(this@Builder)
                        }
                    )
                    inheritancePropagatedDeclarations.orderedDeclarations.forEach { it.fir.accept(this@Builder) }
                    // Build the end node
                    buildEndInitializationNode(enclosingEntity) { happensBefore(it) }
                    (scope as? SubgraphScope.StaticScope)?.lastConstructedNode = enclosingEntity.endSubgraphIndex
                    // NOTE: no classifiers should be accessible from an enum entry's anonymous object, as the enum entry has the type of
                    // its (parent) enum class
                }
            }

        override fun visitConstructor(constructor: FirConstructor): Unit = constructor.visit {
            delegatedConstructor?.accept(this@Builder)
            body?.accept(this@Builder)
        }

        override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer): Unit =
            anonymousInitializer.visit {
                visitingEntity?.let { visitingEntity ->
                    buildDeclarationNode(
                        index = NodeIndex.AnonymousInitializerIndex(visitingEntity, symbol),
                        new = DependencyNode<FirAnonymousInitializer>::AnonymousInitializerNode,
                        connect = { happensBefore(it) }
                    ) {
                        acceptChildren(this@Builder)
                    }
                }
            }

        override fun visitProperty(property: FirProperty): Unit = property.visit {
            // Visiting a property declaration means that we are interested in initializing its node, i.e., it is not a local property
            // Distinguish between a property with a subgraph, i.e., a one which result type is a class, or a property without a subgraph (primitive type)
            // So far we only care about only vals
            if (!isLocal && isVal) {
                // We should be visiting a subgraph
                visitingEntity?.let { visitingEntity ->
                    returnTypeRef.coneTypeOrNull?.let { type ->
                        // Case 1: property without a subgraph -> primitive type
                        if (type.isPrimitiveOrNullablePrimitive || type.isUnit || type.isNothing) {
                            buildDeclarationNode(
                                index = NodeIndex.PrimitivePropertyIndex(visitingEntity, symbol),
                                new = DependencyNode<FirProperty>::PrimitivePropertyNode,
                                connect = { happensBefore(it) },
                                init = { acceptChildren(this@Builder) }
                            )
                        }
                        // Case 2: property with a subgraph -> class type
                        else {
                            type.toRegularClassSymbol()?.let { classSymbol ->
                                val enclosingEntity = symbol.asInstancedPropertyEntity(visitingEntity)
                                if (!startedInitializing.add(enclosingEntity)) return@let
                                require(enclosingEntity.parentEnclosingEntity == visitingEntity)
                                buildBeginInitializationNode(
                                    enclosingEntity = enclosingEntity,
                                    new = DependencyNode<FirProperty>::InstancedPropertyNode,
                                    connect = { happensBefore(it) },
                                    init = {
                                        // Skip building a graph for static initialization of its library type
                                        if (!classSymbol.isLibraryDeclaration) {
                                            // The type's clinit (and of its supertypes) happens before the property's initialization
                                            classSymbol.asClassEntity()?.let { parentEnclosingEntity ->
                                                // Visit the class' declaration as well, as its initialization triggers its clinit
                                                if (parentEnclosingEntity !in startedInitializing) classSymbol.fir.accept(this@Builder)
                                                if (parentEnclosingEntity in startedInitializing) {
                                                    parentEnclosingEntity.endSubgraphIndex.happensBefore(enclosingEntity.beginSubgraphIndex)
                                                }
                                            } ?: enclosingEntity.visitSuperTypes()
                                        }
                                        // Visit the available subexpressions of the property declaration
                                        scope?.let { currentScope ->
                                            // Push the scope of the parent enclosing entity with the begin node as the last constructed node,
                                            // to resolve property declaration dependencies
                                            scopes.push(
                                                SubgraphScope.StaticScope(
                                                    visitingEntity = visitingEntity,
                                                    lastConstructedNode = enclosingEntity.beginSubgraphIndex,
                                                    firstUses = currentScope.firstUses
                                                )
                                            )
                                            try {
                                                // Traverse the property declaration to look for dependencies
                                                acceptChildren(this@Builder)
                                            } finally {
                                                scopes.pop()
                                            }
                                        }
                                    }
                                )
                                // Build the end node
                                buildEndInitializationNode(enclosingEntity) { happensBefore(it) }
                                // Ensure that the last constructed node points to the end node of this subgraph to
                                // maintain the correct happens-before relationship due to initialization order
                                (scope as? SubgraphScope.StaticScope)?.lastConstructedNode = enclosingEntity.endSubgraphIndex
                            }
                        }
                    }
                }
            }
        }

        override fun visitPropertyAccessor(propertyAccessor: FirPropertyAccessor): Unit = propertyAccessor.acceptChildren(this@Builder)

        override fun visitFunction(function: FirFunction): Unit = function.visit {
//            visitingEntity?.let { visitingEntity ->
//                buildDeclarationNode(
//                    index = NodeIndex.FunctionIndex(visitingEntity, symbol),
//                    new = DependencyNode<FirFunction>::FunctionNode,
//                    init = { acceptChildren(this@Builder) }
//                )
//            }
            if (function.symbol.isLibraryDeclaration) return@visit
            acceptChildren(this@Builder)
        }

        override fun visitNamedFunction(namedFunction: FirNamedFunction): Unit = visitFunction(namedFunction)

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction): Unit = visitFunction(anonymousFunction)

        /**
         * =============================================
         *                  EXPRESSIONS
         * =============================================
         */

        override fun visitAnonymousFunctionExpression(anonymousFunctionExpression: FirAnonymousFunctionExpression): Unit =
            anonymousFunctionExpression.visit { acceptChildren(this@Builder) }

        override fun visitAnonymousObjectExpression(anonymousObjectExpression: FirAnonymousObjectExpression): Unit =
            anonymousObjectExpression.visit { acceptChildren(this@Builder) }

        override fun visitBlock(block: FirBlock): Unit = block.visit { acceptChildren(this@Builder) }

        override fun visitBooleanOperatorExpression(booleanOperatorExpression: FirBooleanOperatorExpression): Unit =
            booleanOperatorExpression.visit { acceptChildren(this@Builder) }

        override fun visitCallableReferenceAccess(callableReferenceAccess: FirCallableReferenceAccess): Unit =
            callableReferenceAccess.visit { acceptChildren(this@Builder) }

        override fun visitCheckedSafeCallSubject(checkedSafeCallSubject: FirCheckedSafeCallSubject): Unit =
            checkedSafeCallSubject.visit { originalReceiverRef.value.accept(this@Builder) }

        override fun visitCheckNotNullCall(checkNotNullCall: FirCheckNotNullCall): Unit =
            checkNotNullCall.visit { acceptChildren(this@Builder) }

        override fun visitClassReferenceExpression(classReferenceExpression: FirClassReferenceExpression): Unit =
            classReferenceExpression.visit {
                classTypeRef.toRegularClassSymbol(session)?.asEntity()?.let { classEntity ->
                    when (classEntity) {
                        is EnclosingEntity.Class -> classEntity.markFirstUse()
                        is EnclosingEntity.Object -> classEntity.markFirstUse()
                        else -> {}
                    }
                }
            }

        override fun visitCollectionLiteral(collectionLiteral: FirCollectionLiteral): Unit =
            collectionLiteral.visit { acceptChildren(this@Builder) }

        override fun visitComparisonExpression(comparisonExpression: FirComparisonExpression): Unit =
            comparisonExpression.visit { acceptChildren(this@Builder) }

        override fun visitComponentCall(componentCall: FirComponentCall): Unit = visitFunctionCall(componentCall)

        override fun visitDelegatedConstructorCall(delegatedConstructorCall: FirDelegatedConstructorCall): Unit =
            delegatedConstructorCall.visit {
                calleeReference.toResolvedConstructorSymbol(discardErrorReference = true)?.let {
                    // Skip constructor calls of library declarations, as they cannot create any dependencies
                    if (it.isLibraryDeclaration) return@visit
                    argumentList.acceptChildren(this@Builder)
                    it.fir.accept(this@Builder)
                }
            }

        override fun visitDesugaredAssignmentValueReferenceExpression(
            desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression
        ): Unit = desugaredAssignmentValueReferenceExpression.visit {
            expressionRef.value.accept(this@Builder)
        }

        override fun visitElvisExpression(elvisExpression: FirElvisExpression): Unit =
            elvisExpression.visit { acceptChildren(this@Builder) }

        override fun visitEnumEntryDeserializedAccessExpression(
            enumEntryDeserializedAccessExpression: FirEnumEntryDeserializedAccessExpression
        ): Unit = enumEntryDeserializedAccessExpression.visit {
            enumClassId.toLookupTag().toSymbol()
                ?.fullyExpandedClass()
                ?.collectEnumEntries()
                ?.find { it.name == enumEntryDeserializedAccessExpression.enumEntryName }
                ?.asEnumEntryEntity()
                ?.let { enumEntry ->
                    buildBeginInitializationNode(
                        enclosingEntity = enumEntry.markFirstUse(),
                        new = DependencyNode<FirEnumEntry>::EnumEntryNode,
                        connect = { accesses(enumEntry.beginSubgraphIndex, enumEntryDeserializedAccessExpression) }
                    )
                }
        }

        override fun visitEqualityOperatorCall(equalityOperatorCall: FirEqualityOperatorCall): Unit = equalityOperatorCall.visit {
            acceptChildren(this@Builder)
        }

        private fun FirFunctionCall.toNodeIndex(): NodeIndex.FunctionIndex<*>? =
            calleeReference.toResolvedFunctionSymbol(discardErrorReference = true)?.let { functionSymbol ->
                val enclosingEntity = (dispatchReceiver ?: extensionReceiver)?.let { receiver ->
                    when (receiver) {
                        is FirSuperReceiverExpression, is FirThisReceiverExpression -> scope?.isStatic?.ifTrue {
                            visitingEntity?.correspondingClassSymbol
                                ?.inheritancePropagatedDeclarations
                                ?.let { functionSymbol in it }
                                ?.ifTrue { visitingEntity }
                        }
                        is FirResolvedQualifier -> receiver.toEnclosingEntity()
                        is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                        else -> null
                    }
                } ?: functionSymbol.containingFileSymbol?.asFileEntity()
                enclosingEntity?.let { NodeIndex.FunctionIndex(it, functionSymbol) }
            }

        override fun visitFunctionCall(functionCall: FirFunctionCall): Unit = functionCall.visit {
            // At least check dependencies from the arguments
            argumentList.acceptChildren(this@Builder)
            // If the function is called on a static enclosing entity receiver...
            toNodeIndex()?.let { index ->
                // 1. Build/Get the end node of the outermost enclosing entity of this function node
                val outermostEnclosingEntity = index.enclosingEntity.outermostEntity
                val endNode = buildEndInitializationNode(
                    enclosingEntity = outermostEnclosingEntity,
                    initialize = false,
                )
                // 2. Build/Get the function node and connect it to the end node and the currently constructing node, and initialize it
                val wasInGraph = index in graph
                val node = buildFunctionNode(
                    index = index,
                    connect = { accesses(it, functionCall) },
                    // Visit the function declaration for dependencies if wasn't visited before
                    init = { if (!wasInGraph) index.symbol.fir.accept(this@Builder) }
                ).markFirstUse()
                endNode.index.mayHappenBefore(node.index)
            } ?:
            // If the receiver is either a static primitive property or it is non-static, ...
            functionCall.calleeReference.toResolvedFunctionSymbol(discardErrorReference = true)?.let { functionSymbol ->
                // If the receiver is a static primitive property, ...
                ((dispatchReceiver ?: extensionReceiver) as? FirPropertyAccessExpression)?.let { propertyAccess ->
                    // If the property access is not a primitive type (only possible case), return null
                    if (!propertyAccess.resolvedType.isPrimitiveOrNullablePrimitive
                        && !propertyAccess.resolvedType.isUnit
                        && !propertyAccess.resolvedType.isNothing
                    ) return@let null
                    propertyAccess.calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { propertySymbol ->
                        val enclosingEntity = (propertyAccess.dispatchReceiver ?: propertyAccess.extensionReceiver)?.let { receiver ->
                            when (receiver) {
                                is FirSuperReceiverExpression, is FirThisReceiverExpression -> scope?.isStatic?.ifTrue {
                                    visitingEntity?.correspondingClassSymbol
                                        ?.inheritancePropagatedDeclarations
                                        ?.let { propertySymbol in it }
                                        ?.ifTrue { visitingEntity }
                                }
                                is FirResolvedQualifier -> receiver.toEnclosingEntity()
                                is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                                else -> null
                            }
                        } ?: propertySymbol.containingFileSymbol?.asFileEntity()
                        // If the receiver is static, ...
                        enclosingEntity?.let { enclosingEntity ->
                            enclosingEntity.unwrap()
                            // Connect to the declaration node of the property
                            buildDeclarationNode(
                                // Handle accessor-only properties, i.e., create a may-happen-before cycle to the accessed node,
                                // such that the accesses to this accessor-only property can be checked for uninitialized accesses
                                index = NodeIndex.PrimitivePropertyIndex(
                                    enclosingEntity = enclosingEntity.markFirstUse(allowInnerAccess = !propertySymbol.hasInitializer || scope?.isStatic?.not() ?: false),
                                    symbol = propertySymbol
                                ),
                                new = DependencyNode<FirProperty>::PrimitivePropertyNode,
                                connect = { accesses(it, propertyAccess) }
                            )
                        }
                    }
                } ?:
                // If the receiver is not static, ...
                scope?.let { prevScope ->
                    acceptChildren(this@Builder)
                    // Push a dynamic scope for this function call
                    scopes.push(
                        SubgraphScope.DynamicScope(
                            visitingEntity = prevScope.visitingEntity,
                            lastConstructedNode = prevScope.lastConstructedNode,
                            firstUses = prevScope.firstUses,
                        )
                    )
                    // We traverse the function body and add its dependencies to the currently constructing node
                    functionSymbol.fir.accept(this@Builder)
                    scopes.pop()
                }
            }
        }

        override fun visitGetClassCall(getClassCall: FirGetClassCall): Unit = getClassCall.visit {
            argument.acceptChildren(this@Builder)
        }

        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall): Unit =
            visitFunctionCall(implicitInvokeCall)

        override fun visitIncrementDecrementExpression(incrementDecrementExpression: FirIncrementDecrementExpression): Unit =
            incrementDecrementExpression.visit { acceptChildren(this@Builder) }

        override fun visitIntegerLiteralOperatorCall(integerLiteralOperatorCall: FirIntegerLiteralOperatorCall): Unit =
            visitFunctionCall(integerLiteralOperatorCall)

        override fun visitMultiDelegatedConstructorCall(
            multiDelegatedConstructorCall: FirMultiDelegatedConstructorCall
        ): Unit = Unit

        override fun visitNamedArgumentExpression(namedArgumentExpression: FirNamedArgumentExpression): Unit =
            namedArgumentExpression.visit { acceptChildren(this@Builder) }

        fun EnclosingEntity<*>.unwrap() {
            // If the entity was not encountered before, so the graph has no begin node nor end node for this entity,
            // i.e., its parent must also be missing
            if (this !in graph) {
                // Recursively unwrap its parent
                parentEnclosingEntity?.unwrap()
            }
            // The entity was encountered before during construction of its parent entity (or it was unwrapped), i.e.,
            // begin node and end node are present. However, there are no nodes constructed between them
            if (beginSubgraphIndex in graph && endSubgraphIndex in graph && graph[this].countUntil(2) { it !is NodeIndex.FunctionIndex<*> }) {
                // We don't care that the first uses are not preserved, because if there exist earlier uses, they already create a cycle,
                // so the new uses created here are going to be transitively subsumed by an earlier cycle
                scopes.push(
                    SubgraphScope.StaticScope(
                        visitingEntity = this,
                        lastConstructedNode = beginSubgraphIndex
                    )
                )
                try {
                    correspondingClassSymbol?.inheritancePropagatedDeclarations
                        ?.orderedDeclarations
                        ?.forEach { it.fir.accept(this@Builder) }
                    buildEndInitializationNode(
                        enclosingEntity = this,
                        initialize = false,
                        connect = { happensBefore(it) }
                    )
                } finally {
                    scopes.pop()
                }
            }
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression): Unit =
            propertyAccessExpression.visit {
                // Case 1: accessing an enum entry
                if (resolvedType.isEnum) {
                    calleeReference.toResolvedEnumEntrySymbol(discardErrorReference = true)?.asEnumEntryEntity()?.let { enumEntry ->
                        buildBeginInitializationNode(
                            enclosingEntity = enumEntry.markFirstUse(),
                            new = DependencyNode<FirEnumEntry>::EnumEntryNode,
                            connect = { accesses(enumEntry.beginSubgraphIndex, propertyAccessExpression) }
                        )
                    }
                }
                // Case 2: accessing an instanced property
                else if (resolvedType.toRegularClassSymbol(session) != null
                    && !resolvedType.isPrimitiveOrNullablePrimitive
                    && !resolvedType.isUnit && !resolvedType.isNothing
                ) {
                    calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { propertySymbol ->
                        val outerEnclosingEntity = (dispatchReceiver ?: extensionReceiver)?.let { receiver ->
                            when (receiver) {
                                is FirSuperReceiverExpression, is FirThisReceiverExpression -> scope?.isStatic?.ifTrue {
                                    visitingEntity?.correspondingClassSymbol
                                        ?.inheritancePropagatedDeclarations
                                        ?.let { propertySymbol in it }
                                        ?.ifTrue { visitingEntity }
                                }
                                is FirResolvedQualifier -> receiver.toEnclosingEntity()
                                is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                                else -> null
                            }
                        } ?: propertySymbol.containingFileSymbol?.asFileEntity()
                        // If the receiver is static, ...
                        outerEnclosingEntity?.let { outerEnclosingEntity ->
                            outerEnclosingEntity.unwrap()
                            // Connect to the declaration node of the property
                            val enclosingEntity = propertySymbol.asInstancedPropertyEntity(outerEnclosingEntity)
                            buildBeginInitializationNode(
                                // Handle accessor-only properties, i.e., create a may-happen-before cycle to the accessed node,
                                // such that the accesses to this accessor-only property can be checked for uninitialized accesses
                                enclosingEntity = enclosingEntity.markFirstUse(allowInnerAccess = !propertySymbol.hasInitializer || scope?.isStatic?.not() ?: false),
                                new = DependencyNode<FirProperty>::InstancedPropertyNode,
                                connect = { accesses(it, propertyAccessExpression) }
                            )
                        } ?:
                        // Otherwise, if the receiver is dynamic, ...
                        scope?.let { prevScope ->
                            acceptChildren(this@Builder)
                            // Push a dynamic scope for this function call
                            scopes.push(
                                SubgraphScope.DynamicScope(
                                    visitingEntity = prevScope.visitingEntity,
                                    lastConstructedNode = prevScope.lastConstructedNode,
                                    firstUses = prevScope.firstUses,
                                )
                            )
                            // We traverse the property access subexpressions and the property declaration to look for dependencies
                            propertySymbol.fir.initializer?.accept(this@Builder)
                            propertySymbol.fir.getter?.accept(this@Builder)
                            scopes.pop()
                        }
                    }
                }
                // Case 3: accessing a property with a primitive type
                else {
                    calleeReference.toResolvedPropertySymbol(discardErrorReference = true)?.let { propertySymbol ->
                        val enclosingEntity = (dispatchReceiver ?: extensionReceiver).let { receiver ->
                            when (receiver) {
                                is FirSuperReceiverExpression, is FirThisReceiverExpression -> scope?.isStatic?.ifTrue {
                                    visitingEntity?.correspondingClassSymbol
                                        ?.inheritancePropagatedDeclarations
                                        ?.let { propertySymbol in it }
                                        ?.ifTrue { visitingEntity }
                                }
                                is FirResolvedQualifier -> receiver.toEnclosingEntity()
                                is FirPropertyAccessExpression -> receiver.toEnclosingEntity()
                                else -> null
                            }
                        } ?: propertySymbol.containingFileSymbol?.asFileEntity()
                        // If the receiver is static, ...
                        enclosingEntity?.let { enclosingEntity ->
                            enclosingEntity.unwrap()
                            // Connect to the declaration node of the property
                            buildDeclarationNode(
                                // Handle accessor-only properties, i.e., create a may-happen-before cycle to the accessed node,
                                // such that the accesses to this accessor-only property can be checked for uninitialized accesses
                                index = NodeIndex.PrimitivePropertyIndex(
                                    enclosingEntity = enclosingEntity.markFirstUse(allowInnerAccess = !propertySymbol.hasInitializer || scope?.isStatic?.not() ?: false),
                                    symbol = propertySymbol
                                ),
                                new = DependencyNode<FirProperty>::PrimitivePropertyNode,
                                connect = { accesses(it, propertyAccessExpression) }
                            )
                        } ?:
                        // Otherwise, if the receiver is dynamic, ...
                        scope?.let { prevScope ->
                            acceptChildren(this@Builder)
                            // Push a dynamic scope for this function call
                            scopes.push(
                                SubgraphScope.DynamicScope(
                                    visitingEntity = prevScope.visitingEntity,
                                    lastConstructedNode = prevScope.lastConstructedNode,
                                    firstUses = prevScope.firstUses,
                                )
                            )
                            // We traverse the property access subexpressions and the property declaration to look for dependencies
                            propertySymbol.fir.initializer?.accept(this@Builder)
                            propertySymbol.fir.getter?.accept(this@Builder)
                            scopes.pop()
                        }
                    }
                }
            }

        override fun visitQualifiedAccessExpression(qualifiedAccessExpression: FirQualifiedAccessExpression): Unit = Unit

        override fun visitResolvedQualifier(resolvedQualifier: FirResolvedQualifier): Unit =
            resolvedQualifier.visit {
                // Consider only objects, enum entries are accessible as properties (variables), and (static) classes are not accessible
                if (canBeValue) {
                    symbol?.fullyExpandedClass(session)?.apply {
                        // Case 1: a companion object
                        if (resolvedToCompanionObject) {
                            resolvedCompanionObjectSymbol?.asObjectEntity(asClassEntity())?.let { enclosingEntity ->
                                buildBeginInitializationNode(
                                    enclosingEntity = enclosingEntity.markFirstUse(),
                                    new = DependencyNode<FirRegularClass>::QualifierNode,
                                    connect = { accesses(it, resolvedQualifier) }
                                )
                            }
                        }
                        // Case 2: an object
                        else if (classKind.isObject) {
                            asObjectEntity()?.let { enclosingEntity ->
                                buildBeginInitializationNode(
                                    enclosingEntity = enclosingEntity.markFirstUse(),
                                    new = DependencyNode<FirRegularClass>::QualifierNode,
                                    connect = { accesses(it, resolvedQualifier) }
                                )
                            }
                        }
                    }
                }
            }

        override fun visitReturnExpression(returnExpression: FirReturnExpression): Unit = returnExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitSafeCallExpression(safeCallExpression: FirSafeCallExpression): Unit = safeCallExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitSmartCastExpression(smartCastExpression: FirSmartCastExpression): Unit =
            smartCastExpression.visit { acceptChildren(this@Builder) }

        override fun visitSpreadArgumentExpression(spreadArgumentExpression: FirSpreadArgumentExpression): Unit =
            spreadArgumentExpression.visit { acceptChildren(this@Builder) }

        override fun visitStringConcatenationCall(stringConcatenationCall: FirStringConcatenationCall): Unit =
            stringConcatenationCall.visit { acceptChildren(this@Builder) }

        override fun visitThrowExpression(throwExpression: FirThrowExpression): Unit = throwExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitTryExpression(tryExpression: FirTryExpression): Unit = tryExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitWhenExpression(whenExpression: FirWhenExpression): Unit = whenExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitWhenSubjectExpression(whenSubjectExpression: FirWhenSubjectExpression): Unit = whenSubjectExpression.visit {
            acceptChildren(this@Builder)
        }

        override fun visitWrappedArgumentExpression(wrappedArgumentExpression: FirWrappedArgumentExpression): Unit =
            wrappedArgumentExpression.visit { acceptChildren(this@Builder) }

        override fun visitWrappedDelegateExpression(wrappedDelegateExpression: FirWrappedDelegateExpression): Unit =
            wrappedDelegateExpression.visit { acceptChildren(this@Builder) }

        override fun visitWrappedExpression(wrappedExpression: FirWrappedExpression): Unit = wrappedExpression.visit {
            acceptChildren(this@Builder)
        }
    }
}

val FirSession.dependencyGraph: DependencyGraph by FirSession.sessionComponentAccessor()