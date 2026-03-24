/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.core

import org.jetbrains.kotlin.commonizer.CommonizerTarget
import org.jetbrains.kotlin.commonizer.cir.CirAnnotation
import org.jetbrains.kotlin.commonizer.cir.CirClass
import org.jetbrains.kotlin.commonizer.cir.CirClassOrTypeAliasType
import org.jetbrains.kotlin.commonizer.cir.CirClassType
import org.jetbrains.kotlin.commonizer.cir.CirEntityId
import org.jetbrains.kotlin.commonizer.cir.CirExtensionReceiver
import org.jetbrains.kotlin.commonizer.cir.CirFlexibleType
import org.jetbrains.kotlin.commonizer.cir.CirFunction
import org.jetbrains.kotlin.commonizer.cir.CirFunctionOrProperty
import org.jetbrains.kotlin.commonizer.cir.CirProperty
import org.jetbrains.kotlin.commonizer.cir.CirRegularTypeProjection
import org.jetbrains.kotlin.commonizer.cir.CirType
import org.jetbrains.kotlin.commonizer.cir.CirTypeAliasType
import org.jetbrains.kotlin.commonizer.cir.CirValueParameter
import org.jetbrains.kotlin.commonizer.cir.expandedType
import org.jetbrains.kotlin.commonizer.mergedtree.*
import java.io.File

val typealiasesStatsDirectory = File("/tmp/cinterop-dumps")
val statsDirectory = File("/tmp/cinterop-dumps-2")

private val pattern = """^\d+\. (\w+) <-(?: ([a-zA-Z0-9._]+) <-)? ([a-zA-Z0-9._]+)$""".toRegex()

private val INTEGER_TYPES = listOf("Byte", "Short", "Int", "Long", "UByte", "UShort", "UInt", "ULong", "Char")
private val integerTypesHashSet = INTEGER_TYPES.toHashSet()

private const val UNSAFE_COMMONIZATION_TAG = "unsafe"
private const val INT_COMMONIZATION_TAG = "int"

internal class IntegerStatisticsVisitor(
    private val targets: List<CommonizerTarget>,
) : CirNodeVisitor<Unit, Unit> {
    private lateinit var typealiasesToIntegers: Map<String, TypeAlias>

    private lateinit var currentModuleDirectory: File

    private val targetFileName = targets.joinToString(" + ")

    private val currentFile: File
        get() = currentModuleDirectory.resolve(targetFileName)

    private val longestTargetLength = targets.maxOf { it.toString().length }
        .let { maxOf(it, ("$UNSAFE_COMMONIZATION_TAG $INT_COMMONIZATION_TAG").length) }

    private lateinit var currentPackage: CirPackageNode

    override fun visitRootNode(node: CirRootNode, data: Unit) {
        statsDirectory.mkdir()

        require(typealiasesStatsDirectory.exists()) { "Typealiases stats directory does not exist: ${typealiasesStatsDirectory.path}" }
        loadTypealiasesStats()

        node.modules.values.forEach { module ->
            module.accept(this, Unit)
        }
    }

    data class TypeAlias(val targetType: String, val sourceType: String, val platform: String)

    private fun loadTypealiasesStats() {
        val dirs = (typealiasesStatsDirectory.listFiles() ?: arrayOf())
            .filterNotNull()
            .filter { it.isDirectory }
//            .filter { it.name.startsWith("ios") || it.name.startsWith("macos") || it.name.startsWith("tvos") || it.name.startsWith("watchos") }
//            .filterNot { it.name in listOf("macos_x64", "watchos_arm32", "watchos_arm64") }

        val typealiases = dirs.flatMap { d ->
            (d.listFiles() ?: arrayOf())
                .asSequence()
                .filterNotNull()
                .flatMap { it.readLines() }
                .mapNotNull { pattern.matchEntire(it) }
                .map {
                    val finalExpansion = it.groupValues[1]
//                    val immediateExpansion = it.groupValues[2]
                    val typealiasName = it.groupValues[3]
                    TypeAlias(finalExpansion, typealiasName, d.name)
                }
        }

        val allTypealiasesMap = typealiases.associateBy { it.sourceType }.toMutableMap()

        val data = typealiases.groupBy { it.sourceType }
            .mapValues { (_, v) -> v.groupBy({ it.targetType }, { it.platform }) }

        val problematicData = data.filter { it.value.size > 1 }
        val problematicDataGroups = problematicData.entries.groupBy({ it.value }, { it.key })

        val sortedGroups = problematicDataGroups.entries.sortedByDescending { it.value.size }
        val whitelisted = sortedGroups.take(10).flatMap { (_, aliases) -> aliases }.toHashSet()
        val blacklisted = allTypealiasesMap.keys - whitelisted

        for (allowed in blacklisted) {
            allTypealiasesMap.remove(allowed)
        }

        typealiasesToIntegers = allTypealiasesMap
    }

    override fun visitModuleNode(node: CirModuleNode, data: Unit) {
        val commonized = node.commonDeclaration() ?: return
        val moduleName = commonized.name.name.let { it.substring(1, it.length - 1) }

        currentModuleDirectory = statsDirectory.resolve(moduleName)
        currentModuleDirectory.mkdir()

        node.packages.values.forEach { pkg ->
            pkg.accept(this, Unit)
        }
    }

    @Suppress("DuplicatedCode")
    override fun visitPackageNode(node: CirPackageNode, data: Unit) {
        currentPackage = node

        node.properties.values.forEach { property ->
            property.accept(this, Unit)
        }

        node.functions.values.forEach { function ->
            function.accept(this, Unit)
        }

        node.classes.values.forEach { clazz ->
            clazz.accept(this, Unit)
        }

        node.typeAliases.values.forEach { typeAlias ->
            typeAlias.accept(this, Unit)
        }
    }

    @Suppress("DuplicatedCode")
    override fun visitClassNode(node: CirClassNode, data: Unit) {
        node.constructors.values.forEach { constructor ->
            constructor.accept(this, Unit)
        }

        node.properties.values.forEach { property ->
            property.accept(this, Unit)
        }

        node.functions.values.forEach { function ->
            function.accept(this, Unit)
        }

        node.classes.values.forEach { clazz ->
            clazz.accept(this, Unit)
        }
    }

    override fun visitTypeAliasNode(node: CirTypeAliasNode, data: Unit) {}

    override fun visitClassConstructorNode(node: CirClassConstructorNode, data: Unit) {

    }

    override fun visitPropertyNode(node: CirPropertyNode, data: Unit) {
        val commonized = node.commonDeclaration() ?: return
        visitFunctionOrPropertyNode(commonized, node.targetDeclarations)
    }

    override fun visitFunctionNode(node: CirFunctionNode, data: Unit) {
        val commonized = node.commonDeclaration() ?: return
        visitFunctionOrPropertyNode(commonized, node.targetDeclarations)
    }

    private fun visitFunctionOrPropertyNode(commonized: CirFunctionOrProperty, targetDeclarations: List<CirFunctionOrProperty?>) {
        val rendered = commonized.render()

        val isUnsafeCommonization = targetDeclarations.mapNotNull { it?.collectTypes()?.unwrapAll() }.toSet().size >= 2
        val unsafeTag = if (isUnsafeCommonization) UNSAFE_COMMONIZATION_TAG else null
        val intTag = if (commonized.containsTypealiasesToIntegers()) INT_COMMONIZATION_TAG else null
        val tags = listOfNotNull(unsafeTag, intTag).joinToString(" ")

        currentFile.appendText("| %-${longestTargetLength}s | %s |\n".format(tags, rendered))
        currentFile.appendText("| " + "-".repeat(longestTargetLength) + " | " + "-".repeat(rendered.length) + " |\n")

        for ((it, target) in targetDeclarations.zip(targets)) {
            currentFile.appendText("| %-${longestTargetLength}s | %s |\n".format(target, it?.render()))
        }

        currentFile.appendText("\n")
    }

    private fun CirFunctionOrProperty.containsTypealiasesToIntegers(): Boolean =
        collectTypes().any { it?.mentionsIntegers == true }

    private fun CirFunctionOrProperty.collectTypes(): List<CirType?> = when (this) {
        is CirFunction -> listOf(extensionReceiver?.type) + valueParameters.map { it.returnType } + returnType
        is CirProperty -> listOf(extensionReceiver?.type, returnType)
        else -> error("Unexpected CirFunctionOrProperty: $this")
    }

    private fun List<CirType?>.unwrapAll(): List<CirType?> =
        map { (it as? CirClassOrTypeAliasType)?.unwrapTypealias() ?: it }

    private val CirType.mentionsIntegers: Boolean
        get() = when (this) {
            is CirFlexibleType -> lowerBound.mentionsIntegers || upperBound.mentionsIntegers
            !is CirClassOrTypeAliasType -> false
            else -> unwrapTypealias().let { unwrappedType ->
                unwrappedType.classifierId.isIntegerType
                        || unwrappedType.arguments.any { it is CirRegularTypeProjection && it.type.mentionsIntegers }
            }
        }

    private val CirEntityId.isIntegerType: Boolean
        get() = packageName.toString() == "kotlin"
                && relativeNameSegments.singleOrNull()?.name in integerTypesHashSet

    private fun CirClassOrTypeAliasType.unwrapTypealias(): CirClassType {
        var current = this

        while (current is CirTypeAliasType) {
            current = current.underlyingType
        }

        require(current is CirClassType)
        return current
    }

    private fun CirFunctionOrProperty.render(): String = buildString { renderFunctionOrProperty(this@render) }

    private fun StringBuilder.renderFunctionOrProperty(functionOrProperty: CirFunctionOrProperty) {
//        renderAnnotations(function.annotations)
        when (functionOrProperty) {
            is CirFunction -> append("fun ")
            is CirProperty -> append(if (functionOrProperty.isVar) "var " else "val ")
        }
        append(currentPackage.packageName.toString())
        append("/")
        val containingClass = functionOrProperty.containingClass
        if (containingClass is CirClass) {
            append(containingClass.name.name)
            append("::")
        }
        val extensionReceiver = functionOrProperty.extensionReceiver
        if (extensionReceiver != null) {
            renderExtensionReceiver(extensionReceiver)
            append(".")
        }
        append(functionOrProperty.name)

        if (functionOrProperty is CirFunction) {
            append("(")
            append(functionOrProperty.valueParameters.joinToString(", ") {
                buildString { renderValueParameter(it) }
            })
            append(")")
        }

        append(": ")
        renderType(functionOrProperty.returnType)
    }

    private fun StringBuilder.renderValueParameter(valueParameter: CirValueParameter) {
//        renderAnnotations(valueParameter.annotations)
        append(valueParameter.name)
        append(": ")
        renderType(valueParameter.returnType)
    }

    private fun StringBuilder.renderType(type: CirType) {
        if (type is CirTypeAliasType) {
            append(type.classifierId)
            if (type.arguments.isNotEmpty()) type.arguments.joinTo(this, prefix = "<", postfix = ">") {
                when (it) {
                    is CirRegularTypeProjection -> buildString {
                        append(it.projectionKind)
                        if (isNotEmpty()) append(' ')
                        renderType(it.type)
                    }
                    else -> it.toString()
                }
            }
            append(" -> ")
            append(type.expandedType())
        } else {
            append(type.toString())
        }
    }

    private fun StringBuilder.renderExtensionReceiver(receiver: CirExtensionReceiver) {
        renderType(receiver.type)
    }

    private fun StringBuilder.renderAnnotations(annotations: List<CirAnnotation>) {
        annotations.forEach {
            renderAnnotationCall(it)
            append(" ")
        }
    }

    private fun StringBuilder.renderAnnotationCall(annotation: CirAnnotation, renderAt: Boolean = true) {
        if (renderAt) append("@")
        append(annotation.type)
        append("(")
        val constants = annotation.constantValueArguments.entries.map { (name, it) -> "$name = $it" }
        val annotations = annotation.annotationValueArguments.entries.map { (name, it) ->
            buildString {
                append(name)
                append(" = ")
                renderAnnotationCall(it, renderAt = false)
                append(" ")
            }
        }
        append(constants.plus(annotations).joinToString(", "))
        append(")")
    }
}
