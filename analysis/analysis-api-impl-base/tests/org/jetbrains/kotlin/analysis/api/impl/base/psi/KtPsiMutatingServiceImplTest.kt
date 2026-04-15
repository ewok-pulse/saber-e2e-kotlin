/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.psi

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.ASTNode
import com.intellij.mock.MockApplication
import com.intellij.mock.MockFileDocumentManagerImpl
import com.intellij.mock.MockProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomModel
import com.intellij.pom.core.impl.PomModelImpl
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.impl.source.codeStyle.IndentHelper
import com.intellij.psi.impl.source.tree.TreeCopyHandler
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDoubleColonExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExperimentalApi
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPsiMutatingService
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.getOrCreateBody
import org.jetbrains.kotlin.psi.psiUtil.astReplace
import org.jetbrains.kotlin.psi.psiUtil.addTypeArgument
import org.jetbrains.kotlin.psi.psiUtil.getOrCreateParameterList
import org.jetbrains.kotlin.psi.psiUtil.getOrCreateValueArgumentList
import org.jetbrains.kotlin.psi.typeRefHelpers.setReceiverTypeReference
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.assertThrows

@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(K1Deprecation::class, KtExperimentalApi::class, KtNonPublicApi::class)
class KtPsiMutatingServiceImplTest {
    private lateinit var rootDisposable: Disposable
    private lateinit var environment: KotlinCoreEnvironment

    private val project: MockProject
        get() = environment.project as MockProject

    private val application: MockApplication
        get() = environment.projectEnvironment.environment.application

    private val psiFactory: KtPsiFactory
        get() = KtPsiFactory(project)

    @BeforeAll
    fun setUp() {
        rootDisposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration.create()
        environment = KotlinCoreEnvironment.createForParallelTests(
            rootDisposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )

        registerMutationSupport()
    }

    @AfterAll
    fun tearDown() {
        ApplicationManager.getApplication().runWriteAction {
            Disposer.dispose(rootDisposable)
        }
    }

    @Test
    fun testAddFirstSuperTypeListEntry() {
        val ktClass = createSingleClass("class A")

        writeAction {
            ktClass.addSuperTypeListEntry(psiFactory.createSuperTypeEntry("B"))
        }

        assertEquals("class A:B", ktClass.containingKtFile.text)
    }

    @Test
    fun testReplacePlaceholderSuperTypeListEntry() {
        val ktClass = createSingleClass("class A : ")

        writeAction {
            ktClass.addSuperTypeListEntry(psiFactory.createSuperTypeEntry("B"))
        }

        assertEquals("class A : B", ktClass.containingKtFile.text)
    }

    @Test
    fun testRemoveSuperTypeListEntry() {
        val ktClass = createSingleClass("class A : B, C")
        val entryToRemove = ktClass.superTypeListEntries.first()

        writeAction {
            ktClass.removeSuperTypeListEntry(entryToRemove)
        }

        assertEquals("class A : C", ktClass.containingKtFile.text)
    }

    @Test
    fun testRemoveLastSuperTypeListEntry() {
        val ktClass = createSingleClass("class A : B")
        val entryToRemove = ktClass.superTypeListEntries.single()

        writeAction {
            ktClass.removeSuperTypeListEntry(entryToRemove)
        }

        assertEquals("class A ", ktClass.containingKtFile.text)
    }

    @Test
    fun testSuperTypeListMutation() {
        val ktClass = createSingleClass("class A : B")
        val superTypeList = ktClass.getSuperTypeList()!!

        writeAction {
            superTypeList.addEntry(psiFactory.createSuperTypeEntry("C"))
            superTypeList.removeEntry(superTypeList.entries.first())
        }

        assertEquals(listOf("C"), superTypeList.entries.map { it.text })
        assertEquals("class A : C", ktClass.containingKtFile.text)
    }

    @Test
    fun testRemoveLastSuperTypeListEntryFromSuperTypeList() {
        val ktClass = createSingleClass("class A : B")
        val superTypeList = ktClass.getSuperTypeList()!!

        writeAction {
            superTypeList.removeEntry(superTypeList.entries.single())
        }

        assertNull(ktClass.getSuperTypeList())
        assertEquals("class A ", ktClass.containingKtFile.text)
    }

    @Test
    fun testGenericModifierMutation() {
        val function = createKtFile("fun foo() {}").declarations.single() as KtNamedFunction

        writeAction {
            function.addModifier(KtTokens.PRIVATE_KEYWORD)
        }
        assertTrue(function.hasModifier(KtTokens.PRIVATE_KEYWORD))
        assertEquals("private fun foo() {}", function.containingKtFile.text)

        writeAction {
            function.removeModifier(KtTokens.PRIVATE_KEYWORD)
        }
        assertFalse(function.hasModifier(KtTokens.PRIVATE_KEYWORD))
        assertEquals("fun foo() {}", function.containingKtFile.text)
    }

    @Test
    fun testPrimaryConstructorHelperPublicModifier() {
        val constructor = createPrimaryConstructor("class A constructor()")

        writeAction {
            org.jetbrains.kotlin.psi.addRemoveModifier.addModifier(constructor, KtTokens.PUBLIC_KEYWORD)
        }

        assertEquals("class A public constructor()", constructor.containingKtFile.text)
    }

    @Test
    fun testPrimaryConstructorHelperKeepsConstructorKeyword() {
        val constructor = createPrimaryConstructor("class A private constructor()")

        writeAction {
            org.jetbrains.kotlin.psi.addRemoveModifier.removeModifier(constructor, KtTokens.PRIVATE_KEYWORD)
        }

        assertNull(constructor.modifierList)
        assertNotNull(constructor.getConstructorKeyword())
        assertEquals("class A constructor()", constructor.containingKtFile.text)
    }

    @Test
    fun testPrimaryConstructorModifierRemoval() {
        val constructor = createPrimaryConstructor("class A private constructor()")

        writeAction {
            constructor.removeModifier(KtTokens.PRIVATE_KEYWORD)
        }

        assertNull(constructor.modifierList)
        assertNull(constructor.getConstructorKeyword())
        assertEquals("class A()", constructor.containingKtFile.text)
    }

    @Test
    fun testPrimaryConstructorAnnotationCreation() {
        val constructor = createPrimaryConstructor("class A constructor()")

        writeAction {
            constructor.addAnnotationEntry(psiFactory.createAnnotationEntry("@Anno"))
        }

        assertEquals(listOf("@Anno"), constructor.annotationEntries.map { it.text })
        assertEquals("class A @Annoconstructor()", constructor.containingKtFile.text)
    }

    @Test
    fun testPrimaryConstructorHelperAnnotation() {
        val constructor = createPrimaryConstructor("class A private constructor()")

        writeAction {
            constructor.removeModifier(KtTokens.PRIVATE_KEYWORD)
            org.jetbrains.kotlin.psi.addRemoveModifier.addAnnotationEntry(constructor, psiFactory.createAnnotationEntry("@Anno"))
        }

        assertNull(constructor.getConstructorKeyword())
        assertEquals(listOf("@Anno"), constructor.annotationEntries.map { it.text })
        assertEquals("class A@Anno()", constructor.containingKtFile.text)
    }

    @Test
    fun testExplicitThisDelegation() {
        val constructor = createSecondaryConstructor("class A() { constructor(x: Int) }")

        writeAction {
            constructor.replaceImplicitDelegationCallWithExplicit(isThis = true)
        }

        assertEquals("class A() { constructor(x: Int):this() }", constructor.containingKtFile.text)
    }

    @Test
    fun testExplicitSuperDelegation() {
        val constructor = createSecondaryConstructor("open class B\nclass A : B { constructor(x: Int) }")

        writeAction {
            constructor.replaceImplicitDelegationCallWithExplicit(isThis = false)
        }

        assertEquals("open class B\nclass A : B { constructor(x: Int):super() }", constructor.containingKtFile.text)
    }

    @Test
    fun testDeleteClassFromMultiDeclarationFile() {
        val file = createKtFile("class A\nclass B")
        val declaration = file.declarations.first() as KtClass

        writeAction {
            declaration.delete()
        }

        assertTrue(file.isValid)
        assertEquals(listOf("B"), file.declarations.map { (it as KtClass).name })
        assertEquals("class B", file.text.trim())
    }

    @Test
    fun testDeleteProperty() {
        val file = createKtFile("val a = 1; val b = 2")
        val property = file.declarations.first() as KtProperty

        writeAction {
            property.delete()
        }

        assertEquals(" val b = 2", file.text)
    }

    @Test
    fun testDeleteSuperTypeList() {
        val ktClass = createSingleClass("class A : B")
        val superTypeList = ktClass.getSuperTypeList()!!

        writeAction {
            superTypeList.delete()
        }

        assertNull(ktClass.getSuperTypeList())
        assertEquals("class A ", ktClass.containingKtFile.text)
    }

    @Test
    fun testDeleteEnumEntry() {
        val ktClass = createSingleClass("enum class E { A, B; fun foo() {} }")
        val enumEntry = ktClass.body!!.enumEntries.last()

        writeAction {
            enumEntry.delete()
        }

        assertEquals("enum class E { A;  fun foo() {} }", ktClass.containingKtFile.text)
    }

    @Test
    fun testDeleteOnlyEnumEntry() {
        val ktClass = createSingleClass("enum class E { A; fun foo() {} }")
        val enumEntry = ktClass.body!!.enumEntries.single()

        writeAction {
            enumEntry.delete()
        }

        assertEquals("enum class E { ; fun foo() {} }", ktClass.containingKtFile.text)
    }

    @Test
    fun testAddDeclarationCreatesBody() {
        val ktClass = createSingleClass("class A")

        writeAction {
            ktClass.addDeclaration(psiFactory.createFunction("fun foo() {}"))
        }

        assertNotNull(ktClass.body)
        assertEquals(listOf("fun foo() {}"), ktClass.declarations.map { it.text })
    }

    @Test
    fun testAddDeclarationAfter() {
        val ktClass = createSingleClass("class A { fun first() {} fun third() {} }")
        val anchor = ktClass.declarations.first()

        writeAction {
            ktClass.addDeclarationAfter(psiFactory.createFunction("fun second() {}"), anchor)
        }

        assertEquals(listOf("fun first() {}", "fun second() {}", "fun third() {}"), ktClass.declarations.map { it.text })
    }

    @Test
    fun testAddDeclarationBefore() {
        val ktClass = createSingleClass("class A { fun second() {} fun third() {} }")
        val anchor = ktClass.declarations.first()

        writeAction {
            ktClass.addDeclarationBefore(psiFactory.createFunction("fun first() {}"), anchor)
        }

        assertEquals(listOf("fun first() {}", "fun second() {}", "fun third() {}"), ktClass.declarations.map { it.text })
    }

    @Test
    fun testAddDeclarationToEnumClassAddsSemicolon() {
        val ktClass = createSingleClass("enum class E { A }")

        writeAction {
            ktClass.addDeclaration(psiFactory.createFunction("fun foo() {}"))
        }

        assertNotNull(ktClass.body!!.enumEntries.single().semicolon)
        assertEquals(listOf("A;", "fun foo() {}"), ktClass.declarations.map { it.text })
    }

    @Test
    fun testGetOrCreateBody() {
        val ktClass = createSingleClass("class A")

        val body = writeAction {
            ktClass.getOrCreateBody()
        }

        assertNotNull(ktClass.body)
        assertTrue(body === ktClass.body)
        assertEquals(emptyList<String>(), ktClass.declarations.map { it.text })
    }

    @Test
    fun testNamedDeclarationSetName() {
        val function = createKtFile("operator fun get(i: Int) = 0").declarations.single() as KtNamedFunction

        writeAction {
            function.setName("foo")
        }

        assertEquals("foo", function.name)
        assertFalse(function.hasModifier(KtTokens.OPERATOR_KEYWORD))
        assertEquals("fun foo(i: Int) = 0", function.containingKtFile.text)
    }

    @Test
    fun testNonStubbedNamedDeclarationSetName() {
        val file = createKtFile("val (a, b) = pair")
        val entry = PsiTreeUtil.findChildOfType(file, KtDestructuringDeclarationEntry::class.java)!!

        writeAction {
            entry.setName("x")
        }

        assertEquals("val (x, b) = pair", file.text)
    }

    @Test
    fun testLabeledExpressionSetName() {
        val file = createKtFile("fun test() { outer@ while (true) break }")
        val labeledExpression = PsiTreeUtil.findChildOfType(file, KtLabeledExpression::class.java)!!

        writeAction {
            labeledExpression.setName("loop")
        }

        assertEquals("fun test() { loop@ while (true) break }", file.text)
    }

    @Test
    fun testImportAliasSetName() {
        val file = createKtFile("import kotlin.String as OldName")
        val importAlias = file.importDirectives.single().alias!!

        writeAction {
            importAlias.setName("NewName")
        }

        assertEquals("import kotlin.String as NewName", file.text.trim())
    }

    @Test
    fun testObjectDeclarationSetName() {
        val companionObject = createSingleClass("class A { companion object {} }").companionObjects.single()

        writeAction {
            companionObject.setName("Named")
        }

        assertEquals("class A { companion object Named {} }", companionObject.containingKtFile.text)
    }

    @Test
    fun testFileSetName() {
        val file = createKtFile("val value = 1", fileName = "test.kt")

        writeAction {
            file.setName("test.kts")
        }

        assertEquals("test.kts", file.name)
    }

    @Test
    fun testConstructorSetName() {
        val constructor = createPrimaryConstructor("class A constructor()")

        assertThrows<IncorrectOperationException> {
            writeAction {
                constructor.setName("B")
            }
        }
    }

    @Test
    fun testCallableTypeReferenceHelpers() {
        val function = createKtFile("fun foo() {}").declarations.single() as KtNamedFunction

        writeAction {
            function.setTypeReference(psiFactory.createType("Int"))
            function.setReceiverTypeReference(psiFactory.createType("String"))
        }

        assertEquals("fun String.foo():Int {}", function.containingKtFile.text)
    }

    @Test
    fun testFunctionTypeReceiverTypeHelper() {
        val functionType = psiFactory.createType("() -> Unit").typeElement as KtFunctionType

        writeAction {
            functionType.setReceiverTypeReference(psiFactory.createType("String"))
        }

        assertEquals("String.() -> Unit", functionType.text)
    }

    @Test
    fun testPropertyInitializerMutation() {
        val property = createKtFile("val value = 1").declarations.single() as KtProperty

        writeAction {
            property.setInitializer(psiFactory.createExpression("2"))
        }

        assertEquals("val value = 2", property.containingKtFile.text)
    }

    @Test
    fun testTypeParameterMutations() {
        val ktClass = createSingleClass("class Box<T>")
        val typeParameter = ktClass.typeParameters.single()

        writeAction {
            typeParameter.extendsBound = psiFactory.createType("CharSequence")
            ktClass.typeParameterList!!.addParameter(psiFactory.createTypeParameter("U"))
        }

        assertEquals(listOf("T:CharSequence", "U"), ktClass.typeParameterList!!.parameters.map { it.text })
    }

    @Test
    fun testPackageDirectiveAndDoubleColonMutations() {
        val file = createKtFile("package foo\n\nval ref = ::bar\nfun bar() = 1")
        val packageDirective = file.packageDirective!!
        val reference = ((file.declarations.first() as KtProperty).initializer as KtDoubleColonExpression)

        writeAction {
            packageDirective.fqName = FqName("foo.bar")
            reference.setReceiverExpression(psiFactory.createExpression("baz"))
        }

        assertEquals("foo.bar", packageDirective.fqName.asString())
        assertEquals("baz::bar", reference.text)
    }

    @Test
    fun testParameterListMutation() {
        val function = createKtFile("fun foo() {}").declarations.single() as KtNamedFunction
        val parameterList = function.valueParameterList!!

        writeAction {
            parameterList.addParameter(psiFactory.createParameter("x: Int"))
            val anchor = parameterList.parameters.single()
            parameterList.addParameterBefore(psiFactory.createParameter("prefix: String"), anchor)
            parameterList.addParameterAfter(psiFactory.createParameter("suffix: Boolean"), anchor)
        }

        assertEquals(listOf("prefix: String", "x: Int", "suffix: Boolean"), parameterList.parameters.map { it.text })
    }

    @Test
    fun testParameterRemoval() {
        val function = createKtFile("fun foo(a: Int, b: String) {}").declarations.single() as KtNamedFunction
        val parameterList = function.valueParameterList!!

        writeAction {
            parameterList.removeParameter(0)
        }

        assertEquals(listOf("b: String"), parameterList.parameters.map { it.text })
        assertEquals("fun foo( b: String) {}", function.containingKtFile.text)
    }

    @Test
    fun testLambdaParameterListCreation() {
        val functionLiteral =
            (((createKtFile("val lambda = { 42 }").declarations.single() as KtProperty).initializer) as KtLambdaExpression).functionLiteral

        writeAction {
            functionLiteral.getOrCreateParameterList()
        }

        assertNotNull(functionLiteral.valueParameterList)
        assertTrue(functionLiteral.valueParameterList!!.parameters.isEmpty())
        assertNotNull(functionLiteral.arrow)
    }

    @Test
    fun testTypeArgumentListMutation() {
        val typeArgumentList = psiFactory.createTypeArguments("<String>")

        writeAction {
            typeArgumentList.addArgument(psiFactory.createTypeArgument("Int"))
        }

        assertEquals(listOf("String", "Int"), typeArgumentList.arguments.map { it.text })
        assertEquals("<String,Int>", typeArgumentList.text)
    }

    @Test
    fun testCallTypeArgumentMutation() {
        val call = ((createKtFile("val value = foo()").declarations.single() as KtProperty).initializer as KtCallExpression)

        writeAction {
            call.addTypeArgument(psiFactory.createTypeArgument("String"))
        }

        assertEquals("<String>", call.typeArgumentList!!.text)
        assertEquals("foo<String>()", call.text)
    }

    @Test
    fun testValueArgumentListMutation() {
        val file = createKtFile("fun foo(a: Int, b: Int, c: Int) {}\nval value = foo(2)")
        val call = ((file.declarations.last() as KtProperty).initializer as KtCallExpression)
        val argumentList = call.valueArgumentList!!
        val anchor = argumentList.arguments.single()

        writeAction {
            argumentList.addArgumentBefore(psiFactory.createArgument("1"), anchor)
            argumentList.addArgumentAfter(psiFactory.createArgument("3"), anchor)
            argumentList.removeArgument(anchor)
            argumentList.addArgument(psiFactory.createArgument("4"))
        }

        assertEquals(listOf("1", "3", "4"), argumentList.arguments.map { it.text })
    }

    @Test
    fun testCallValueArgumentListCreation() {
        val call = ((createKtFile("val value = foo<String> { 42 }").declarations.single() as KtProperty).initializer as KtCallExpression)

        writeAction {
            call.getOrCreateValueArgumentList()
        }

        assertNotNull(call.valueArgumentList)
        assertTrue(call.valueArgumentList!!.arguments.isEmpty())
        assertTrue(call.text.contains("foo<String>()"))
    }

    @Test
    fun testAstReplace() {
        val function = createKtFile("fun foo() {}").declarations.single() as KtNamedFunction
        val identifier = function.nameIdentifier!!
        val replacement = psiFactory.createSimpleName("bar")

        writeAction {
            identifier.astReplace(replacement)
        }

        assertEquals("fun bar() {}", function.containingKtFile.text)
    }

    @Test
    fun testEnumEntryAddSemicolon() {
        val enumEntry = createSingleClass("enum class E { A, B }").declarations.filterIsInstance<KtEnumEntry>().last()

        writeAction {
            enumEntry.addSemicolon()
        }

        assertEquals("B;", enumEntry.text)
        assertEquals("enum class E { A, B; }", enumEntry.containingKtFile.text)
    }

    @Test
    fun testEnumEntryAddSemicolonFromBody() {
        val body = createSingleClass("enum class E { A; fun foo() {} }").body!!
        val enumEntry = body.enumEntries.single()

        writeAction {
            body.addAfter(psiFactory.createSemicolon(), enumEntry)
            enumEntry.semicolon!!.delete()
            enumEntry.addSemicolon()
        }

        assertEquals("A;", enumEntry.text)
        assertFalse(body.children.any { it.text == ";" && it.parent == body })
    }

    @Test
    fun testRemoveLastAnnotationEntry() {
        val file = createKtFile("@file:[A]\npackage p")
        val annotation = file.fileAnnotationList!!.annotations.single()

        writeAction {
            annotation.removeEntry(annotation.entries.single())
        }

        assertTrue(file.fileAnnotationList?.annotationEntries.orEmpty().isEmpty())
        assertEquals("package p", file.text.trim())
    }

    @Suppress("UnstableApiUsage")
    private fun registerMutationSupport() {
        if (application.getService(KtPsiMutatingService::class.java) == null) {
            application.registerService(KtPsiMutatingService::class.java, KtPsiMutatingServiceImpl())
        }

        if (application.getService(IndentHelper::class.java) == null) {
            application.registerService(IndentHelper::class.java, TestIndentHelper())
        }

        if (application.getService(FileDocumentManager::class.java) !is TestFileDocumentManager) {
            application.picoContainer.unregisterComponent(FileDocumentManager::class.java.name)
            application.registerService(FileDocumentManager::class.java, TestFileDocumentManager())
        }

        if (!application.extensionArea.hasExtensionPoint(DocumentWriteAccessGuard.EP_NAME.name)) {
            CoreApplicationEnvironment.registerExtensionPoint(
                application.extensionArea,
                DocumentWriteAccessGuard.EP_NAME,
                TestDocumentWriteAccessGuard::class.java,
            )
        }

        if (!application.extensionArea.hasExtensionPoint(TreeCopyHandler.EP_NAME.name)) {
            CoreApplicationEnvironment.registerApplicationDynamicExtensionPoint(
                TreeCopyHandler.EP_NAME.name,
                TreeCopyHandler::class.java,
            )
        }

        if (!project.extensionArea.hasExtensionPoint(PsiTreeChangeListener.EP.name)) {
            CoreApplicationEnvironment.registerExtensionPoint(
                project.extensionArea,
                PsiTreeChangeListener.EP.name,
                TestPsiTreeChangeListener::class.java,
            )
        }

        if (project.getService(TreeAspect::class.java) == null) {
            project.registerService(TreeAspect::class.java)
        }

        if (project.getService(PomModel::class.java) == null) {
            project.registerService(PomModel::class.java, PomModelImpl::class.java)
        }

        assertNotNull(application.getService(KtPsiMutatingService::class.java))
    }

    private fun createKtFile(text: String, fileName: String = "test.kt"): KtFile = psiFactory.createFile(fileName, text)

    private fun createSingleClass(text: String): KtClass = createKtFile(text).declarations.single() as KtClass

    private fun createPrimaryConstructor(text: String): KtPrimaryConstructor {
        return createSingleClass(text).primaryConstructor!!
    }

    private fun createSecondaryConstructor(text: String): KtSecondaryConstructor {
        return createKtFile(text).declarations.last().let { declaration ->
            when (declaration) {
                is KtClass -> declaration.secondaryConstructors.single()
                else -> error("Expected class declaration, got ${declaration::class.java.name}")
            }
        }
    }

    private fun <T> writeAction(action: () -> T): T {
        return ApplicationManager.getApplication().runWriteAction<T> {
            action()
        }
    }
}

private class TestFileDocumentManager :
    MockFileDocumentManagerImpl(FileDocumentManagerBase.HARD_REF_TO_DOCUMENT_KEY, { DocumentImpl(it) }) {
    override fun getDocument(file: VirtualFile): Document? {
        val document = super.getDocument(file) ?: return null
        file.putUserDataIfAbsent(FileDocumentManagerBase.HARD_REF_TO_DOCUMENT_KEY, document)
        return document
    }
}

@Suppress("UnstableApiUsage")
private class TestDocumentWriteAccessGuard : DocumentWriteAccessGuard() {
    override fun isWritable(document: Document): Result = success()
}

private class TestIndentHelper : IndentHelper() {
    override fun getIndent(file: PsiFile, element: ASTNode): Int = 0

    override fun getIndent(file: PsiFile, element: ASTNode, includeNonSpace: Boolean): Int = 0
}

private class TestPsiTreeChangeListener : PsiTreeChangeListener {
    override fun beforeChildAddition(event: PsiTreeChangeEvent) {}

    override fun beforeChildRemoval(event: PsiTreeChangeEvent) {}

    override fun beforeChildReplacement(event: PsiTreeChangeEvent) {}

    override fun beforeChildMovement(event: PsiTreeChangeEvent) {}

    override fun beforeChildrenChange(event: PsiTreeChangeEvent) {}

    override fun beforePropertyChange(event: PsiTreeChangeEvent) {}

    override fun childAdded(event: PsiTreeChangeEvent) {}

    override fun childRemoved(event: PsiTreeChangeEvent) {}

    override fun childReplaced(event: PsiTreeChangeEvent) {}

    override fun childrenChanged(event: PsiTreeChangeEvent) {}

    override fun childMoved(event: PsiTreeChangeEvent) {}

    override fun propertyChanged(event: PsiTreeChangeEvent) {}
}
