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
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDoubleColonExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPsiMutatingService
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
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

@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(K1Deprecation::class, KtNonPublicApi::class)
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
    fun addSuperTypeListEntryAddsFirstSupertype() {
        val ktClass = createSingleClass("class A")

        writeAction {
            ktClass.addSuperTypeListEntry(psiFactory.createSuperTypeEntry("B"))
        }

        assertEquals("class A:B", ktClass.containingKtFile.text)
    }

    @Test
    fun addSuperTypeListEntryReplacesPlaceholderEntry() {
        val ktClass = createSingleClass("class A : ")

        writeAction {
            ktClass.addSuperTypeListEntry(psiFactory.createSuperTypeEntry("B"))
        }

        assertEquals("class A : B", ktClass.containingKtFile.text)
    }

    @Test
    fun removeSuperTypeListEntryHandlesCommaSeparatedList() {
        val ktClass = createSingleClass("class A : B, C")
        val entryToRemove = ktClass.superTypeListEntries.first()

        writeAction {
            ktClass.removeSuperTypeListEntry(entryToRemove)
        }

        assertEquals("class A : C", ktClass.containingKtFile.text)
    }

    @Test
    fun removeSuperTypeListEntryRemovesColonForLastEntry() {
        val ktClass = createSingleClass("class A : B")
        val entryToRemove = ktClass.superTypeListEntries.single()

        writeAction {
            ktClass.removeSuperTypeListEntry(entryToRemove)
        }

        assertEquals("class A ", ktClass.containingKtFile.text)
    }

    @Test
    fun superTypeListMutationWorksThroughDeprecatedPsiApi() {
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
    fun removingLastSuperTypeListEntryThroughDeprecatedPsiApiDeletesList() {
        val ktClass = createSingleClass("class A : B")
        val superTypeList = ktClass.getSuperTypeList()!!

        writeAction {
            superTypeList.removeEntry(superTypeList.entries.single())
        }

        assertNull(ktClass.getSuperTypeList())
        assertEquals("class A ", ktClass.containingKtFile.text)
    }

    @Test
    fun genericModifierMutationWorksThroughDeprecatedPsiApi() {
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
    fun primaryConstructorHelperKeepsPublicModifierBehavior() {
        val constructor = createPrimaryConstructor("class A constructor()")

        writeAction {
            org.jetbrains.kotlin.psi.addRemoveModifier.addModifier(constructor, KtTokens.PUBLIC_KEYWORD)
        }

        assertEquals("class A public constructor()", constructor.containingKtFile.text)
    }

    @Test
    fun primaryConstructorHelperKeepsConstructorKeywordOnModifierRemoval() {
        val constructor = createPrimaryConstructor("class A private constructor()")

        writeAction {
            org.jetbrains.kotlin.psi.addRemoveModifier.removeModifier(constructor, KtTokens.PRIVATE_KEYWORD)
        }

        assertNull(constructor.modifierList)
        assertNotNull(constructor.getConstructorKeyword())
        assertEquals("class A constructor()", constructor.containingKtFile.text)
    }

    @Test
    fun primaryConstructorModifierRemovalCleansUpConstructorKeyword() {
        val constructor = createPrimaryConstructor("class A private constructor()")

        writeAction {
            constructor.removeModifier(KtTokens.PRIVATE_KEYWORD)
        }

        assertNull(constructor.modifierList)
        assertNull(constructor.getConstructorKeyword())
        assertEquals("class A()", constructor.containingKtFile.text)
    }

    @Test
    fun primaryConstructorAnnotationCreationPreservesDedicatedBehavior() {
        val constructor = createPrimaryConstructor("class A constructor()")

        writeAction {
            constructor.addAnnotationEntry(psiFactory.createAnnotationEntry("@Anno"))
        }

        assertEquals(listOf("@Anno"), constructor.annotationEntries.map { it.text })
        assertEquals("class A @Annoconstructor()", constructor.containingKtFile.text)
    }

    @Test
    fun primaryConstructorHelperDoesNotRecreateConstructorKeywordForAnnotation() {
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
    fun secondaryConstructorImplicitThisDelegationCanBeMadeExplicit() {
        val constructor = createSecondaryConstructor("class A() { constructor(x: Int) }")

        writeAction {
            constructor.replaceImplicitDelegationCallWithExplicit(isThis = true)
        }

        assertEquals("class A() { constructor(x: Int):this() }", constructor.containingKtFile.text)
    }

    @Test
    fun secondaryConstructorImplicitSuperDelegationCanBeMadeExplicit() {
        val constructor = createSecondaryConstructor("open class B\nclass A : B { constructor(x: Int) }")

        writeAction {
            constructor.replaceImplicitDelegationCallWithExplicit(isThis = false)
        }

        assertEquals("open class B\nclass A : B { constructor(x: Int):super() }", constructor.containingKtFile.text)
    }

    @Test
    fun deletingClassFromMultiDeclarationFileKeepsFile() {
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
    fun callableTypeReferenceHelpersMutateThroughDeprecatedPsiApi() {
        val function = createKtFile("fun foo() {}").declarations.single() as KtNamedFunction

        writeAction {
            function.setTypeReference(psiFactory.createType("Int"))
            function.setReceiverTypeReference(psiFactory.createType("String"))
        }

        assertEquals("fun String.foo():Int {}", function.containingKtFile.text)
    }

    @Test
    fun functionTypeReceiverTypeHelperMutatesThroughDeprecatedPsiApi() {
        val functionType = psiFactory.createType("() -> Unit").typeElement as KtFunctionType

        writeAction {
            functionType.setReceiverTypeReference(psiFactory.createType("String"))
        }

        assertEquals("String.() -> Unit", functionType.text)
    }

    @Test
    fun propertyInitializerMutationWorksThroughDeprecatedPsiApi() {
        val property = createKtFile("val value = 1").declarations.single() as KtProperty

        writeAction {
            property.setInitializer(psiFactory.createExpression("2"))
        }

        assertEquals("val value = 2", property.containingKtFile.text)
    }

    @Test
    fun typeParameterMutationsWorkThroughDeprecatedPsiApi() {
        val ktClass = createSingleClass("class Box<T>")
        val typeParameter = ktClass.typeParameters.single()

        writeAction {
            typeParameter.extendsBound = psiFactory.createType("CharSequence")
            ktClass.typeParameterList!!.addParameter(psiFactory.createTypeParameter("U"))
        }

        assertEquals(listOf("T:CharSequence", "U"), ktClass.typeParameterList!!.parameters.map { it.text })
    }

    @Test
    fun packageDirectiveAndDoubleColonMutationsWorkThroughDeprecatedPsiApi() {
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
    fun parameterListMutationWorksThroughDeprecatedPsiApi() {
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
    fun parameterRemovalWorksThroughDeprecatedPsiApi() {
        val function = createKtFile("fun foo(a: Int, b: String) {}").declarations.single() as KtNamedFunction
        val parameterList = function.valueParameterList!!

        writeAction {
            parameterList.removeParameter(0)
        }

        assertEquals(listOf("b: String"), parameterList.parameters.map { it.text })
        assertEquals("fun foo( b: String) {}", function.containingKtFile.text)
    }

    @Test
    fun typeArgumentListMutationWorksThroughDeprecatedPsiApi() {
        val typeArgumentList = psiFactory.createTypeArguments("<String>")

        writeAction {
            typeArgumentList.addArgument(psiFactory.createTypeArgument("Int"))
        }

        assertEquals(listOf("String", "Int"), typeArgumentList.arguments.map { it.text })
        assertEquals("<String,Int>", typeArgumentList.text)
    }

    @Test
    fun valueArgumentListMutationWorksThroughDeprecatedPsiApi() {
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
    fun removingLastAnnotationEntryWorksThroughDeprecatedPsiApi() {
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
