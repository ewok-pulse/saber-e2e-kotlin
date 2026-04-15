/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dependencies.semantics

import org.jetbrains.kotlin.descriptors.isEnumClass
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.SessionHolder
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.fullyExpandedClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.resolve.dependencies.PathCompressingFinder
import org.jetbrains.kotlin.fir.resolve.dependencies.findCorrespondingEnumEntry
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFileSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

/**
 * Represents the sources of static entities such as properties in objects or top-level properties
 */
sealed class EnclosingEntity<D : FirDeclaration> {

    /**
     * Returns the symbol representing the entity's declaration
     */
    abstract val symbol: FirBasedSymbol<D>

    /**
     * Returns the enclosing entity whose initialization directly triggers the initialization of this entity, if applicable
     */
    abstract val parentEnclosingEntity: EnclosingEntity<*>?

    abstract val beginSubgraphIndex: NodeIndex.BeginSubgraphIndex<D>

    abstract val correspondingClassSymbol: FirClassSymbol<*>?

    val endSubgraphIndex: NodeIndex.EndSubgraphIndex<D> by lazy { NodeIndex.EndSubgraphIndex(beginSubgraphIndex) }

    data class Class(override val symbol: FirRegularClassSymbol) : EnclosingEntity<FirRegularClass>() {
        override val parentEnclosingEntity: EnclosingEntity<*>? get() = null
        override val correspondingClassSymbol: FirClassSymbol<*> get() = symbol
        override val beginSubgraphIndex: NodeIndex.BeginSubgraphIndex<FirRegularClass> = NodeIndex.ClinitIndex(this)
        override fun toString(): String = "${symbol.name}::class"
    }

    data class Object(
        override val symbol: FirRegularClassSymbol,
        override val parentEnclosingEntity: Class? = null
    ) : EnclosingEntity<FirRegularClass>() {
        override val correspondingClassSymbol: FirClassSymbol<*> get() = symbol
        override val beginSubgraphIndex: NodeIndex.QualifierIndex = NodeIndex.QualifierIndex(this)
        override fun toString(): String = parentEnclosingEntity?.let { outerEnclosingEntity ->
            "$outerEnclosingEntity.${symbol.name}"
        } ?: "${symbol.name}"
    }

    data class File(override val symbol: FirFileSymbol) : EnclosingEntity<FirFile>() {
        override val parentEnclosingEntity: EnclosingEntity<*>? get() = null
        override val correspondingClassSymbol: FirClassSymbol<*>? get() = null
        override val beginSubgraphIndex: NodeIndex.TopLevelIndex = NodeIndex.TopLevelIndex(this)
        override fun toString(): String = symbol.fir.name
    }

    data class EnumEntry(override val symbol: FirEnumEntrySymbol) : EnclosingEntity<FirEnumEntry>() {
        override val parentEnclosingEntity: Class = symbol.getContainingClassSymbol()
            ?.fullyExpandedClass(symbol.moduleData.session)
            ?.asClassEntity()
            ?: error("An enum entry entity must always be nested under an enum class entity!")
        override val correspondingClassSymbol: FirClassSymbol<*>? get() = symbol.initializerObjectSymbol
        override val beginSubgraphIndex: NodeIndex.EnumEntryIndex = NodeIndex.EnumEntryIndex(this)
        override fun toString(): String = "$parentEnclosingEntity.${symbol.name}"
    }

    data class InstancedProperty(
        override val symbol: FirPropertySymbol,
        override val parentEnclosingEntity: EnclosingEntity<*>
    ) : EnclosingEntity<FirProperty>() {
        override val correspondingClassSymbol: FirClassSymbol<*>? = symbol.resolvedReturnType
            .fullyExpandedType(symbol.moduleData.session)
            .toRegularClassSymbol(symbol.moduleData.session)
        override val beginSubgraphIndex: NodeIndex.InstancedPropertyIndex = NodeIndex.InstancedPropertyIndex(this)
        override fun toString(): String = "${if (parentEnclosingEntity is File) "" else "$parentEnclosingEntity."}${symbol.name}"
    }

    companion object {
        private fun FirRegularClassSymbol.assertValidOuterClassForObject(outerClass: Class?) {
            require(classKind.isObject) { "Class symbol must be an object!" }
            if (isCompanion && outerClass != null) {
                require(getContainingClassSymbol()?.fullyExpandedClass(moduleData.session)?.resolvedCompanionObjectSymbol != outerClass.symbol) {
                    "Companion object must be nested under the same class as the given outer class!"
                }
            } else if (!isCompanion) {
                require(outerClass == null) { "Regular objects must not be provided with a class entity!" }
            }
        }

        fun FirRegularClassSymbol.asObjectEntity(outerClass: Class? = null): Object? = when (classKind.isObject) {
            true -> {
                assertValidOuterClassForObject(outerClass)
                Object(this, outerClass ?: getContainingClassSymbol()?.fullyExpandedClass(moduleData.session)?.asClassEntity())
            }
            false -> null
        }

        fun FirRegularClassSymbol.asClassEntity(): Class? =
            when (classKind.isEnumClass || resolvedCompanionObjectSymbol != null) {
                true -> Class(this)
                false -> null
            }

        fun FirAnonymousObjectSymbol.asEnumEntryEntity(): EnumEntry? = findCorrespondingEnumEntry()?.let(::EnumEntry)

        fun FirBasedSymbol<*>.asEntity(allowClass: Boolean = true): EnclosingEntity<*>? =
            when (this) {
                is FirRegularClassSymbol -> asObjectEntity() ?: if (allowClass) asClassEntity() else null
                is FirAnonymousObjectSymbol -> asEnumEntryEntity()
                is FirFileSymbol -> asFileEntity()
                else -> null
            }

        fun FirFileSymbol.asFileEntity(): File = File(this)

        fun FirEnumEntrySymbol.asEnumEntryEntity(): EnumEntry = EnumEntry(this)

        fun FirPropertySymbol.asInstancedPropertyEntity(outerEnclosingEntity: EnclosingEntity<*>): InstancedProperty =
            InstancedProperty(this, outerEnclosingEntity)

        object OutermostEnclosingEntityFinder : PathCompressingFinder<EnclosingEntity<*>>({ it.parentEnclosingEntity ?: it }),
            FirSessionComponent

        val FirSession.outermostEntityFinder: OutermostEnclosingEntityFinder by FirSession.sessionComponentAccessor()

        context(sessionHolder: SessionHolder)
        val EnclosingEntity<*>.outermostEntity: EnclosingEntity<*> get() = sessionHolder.session.outermostEntityFinder.find(this)
    }
}
