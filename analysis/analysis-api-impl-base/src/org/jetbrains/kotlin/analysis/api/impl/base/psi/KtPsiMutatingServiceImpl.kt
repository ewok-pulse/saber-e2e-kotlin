/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.CheckUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import org.jetbrains.kotlin.lexer.KtTokens.SEMICOLON
import org.jetbrains.kotlin.psi.KtImplementationDetail
import org.jetbrains.kotlin.psi.KtNonPublicApi
import org.jetbrains.kotlin.psi.KtPsiMutatingService
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.lexer.KtTokens.COLON

@OptIn(KtNonPublicApi::class, KtImplementationDetail::class)
class KtPsiMutatingServiceImpl : KtPsiMutatingService {
    override fun addSuperTypeListEntry(
        declaration: KtClassOrObject,
        superTypeListEntry: KtSuperTypeListEntry,
    ): KtSuperTypeListEntry {
        declaration.getSuperTypeList()?.let { superTypeList ->
            val single = superTypeList.entries.singleOrNull()
            if (single != null && single.typeReference?.typeElement == null) {
                return single.replace(superTypeListEntry) as KtSuperTypeListEntry
            }

            return EditCommaSeparatedListHelper.addItem(superTypeList, declaration.superTypeListEntries, superTypeListEntry)
        }

        val psiFactory = KtPsiFactory(declaration.project)
        val specifierListToAdd = psiFactory.createSuperTypeCallEntry("A()").replace(superTypeListEntry).parent
        val colon = declaration.addBefore(psiFactory.createColon(), declaration.getBody())
        return (declaration.addAfter(specifierListToAdd, colon) as KtSuperTypeList).entries.first()
    }

    override fun addSuperTypeListEntry(
        superTypeList: KtSuperTypeList,
        superTypeListEntry: KtSuperTypeListEntry,
    ): KtSuperTypeListEntry {
        return EditCommaSeparatedListHelper.addItem(superTypeList, superTypeList.entries, superTypeListEntry)
    }

    override fun removeSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry) {
        val specifierList = declaration.getSuperTypeList() ?: return
        assert(superTypeListEntry.parent === specifierList)

        if (specifierList.entries.size > 1) {
            EditCommaSeparatedListHelper.removeItem<KtElement>(superTypeListEntry)
        } else {
            declaration.deleteChildRange(declaration.getColon() ?: specifierList, specifierList)
        }
    }

    override fun removeSuperTypeListEntry(superTypeList: KtSuperTypeList, superTypeListEntry: KtSuperTypeListEntry) {
        EditCommaSeparatedListHelper.removeItem<KtElement>(superTypeListEntry)
        if (superTypeList.entries.isEmpty()) {
            deleteSuperTypeList(superTypeList)
        }
    }

    override fun deleteSuperTypeList(superTypeList: KtSuperTypeList) {
        var left = PsiTreeUtil.skipSiblingsBackward(superTypeList, PsiWhiteSpace::class.java, PsiComment::class.java)
        if (left?.elementType != COLON) {
            left = superTypeList
        }
        superTypeList.parent.deleteChildRange(left, superTypeList)
    }

    override fun deleteClassOrObject(declaration: KtClassOrObject) {
        if (declaration is KtEnumEntry) {
            val semicolon = declaration.semicolon
            if (semicolon != null) {
                // Get previous KtEnumEntry, and move semicolon to it
                val prevEntry = PsiTreeUtil.getPrevSiblingOfType(declaration, KtEnumEntry::class.java)

                if (prevEntry == null) {
                    // if there's no previous KtEnumEntry, we embed it into the parent (expected to be a KtClassBody)
                    val parent = declaration.parent
                    check(parent is KtClassBody) { "Enum entry should be a child of KtClassBody" }
                    parent.addAfter(semicolon, declaration)
                } else {
                    // if there is, we move semicolon to it
                    addSemicolon(prevEntry)
                }
            }
        }

        CheckUtil.checkWritable(declaration)

        val file = declaration.containingKtFile
        if (!declaration.isTopLevel() || file.declarations.size > 1) {
            deleteAsPlainKtElement(declaration)
        } else {
            file.delete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : KtDeclaration> addDeclaration(classOrObject: KtClassOrObject, declaration: T): T {
        ensureSemicolonIsPresentAfterEnumEntriesIfNecessaryForDeclaration(classOrObject, declaration)
        val body = getOrCreateBody(classOrObject)
        val anchor = PsiTreeUtil.skipSiblingsBackward(body.rBrace ?: body.lastChild!!, PsiWhiteSpace::class.java)
        return if (anchor?.nextSibling is PsiErrorElement) {
            body.addBefore(declaration, anchor)
        } else {
            body.addAfter(declaration, anchor)
        } as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : KtDeclaration> addDeclarationAfter(classOrObject: KtClassOrObject, declaration: T, anchor: PsiElement?): T {
        val anchorBefore = anchor ?: classOrObject.declarations.lastOrNull() ?: return addDeclaration(classOrObject, declaration)
        ensureSemicolonIsPresentAfterEnumEntriesIfNecessaryForDeclaration(classOrObject, declaration)
        return getOrCreateBody(classOrObject).addAfter(declaration, anchorBefore) as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : KtDeclaration> addDeclarationBefore(classOrObject: KtClassOrObject, declaration: T, anchor: PsiElement?): T {
        val anchorAfter = anchor ?: classOrObject.declarations.firstOrNull() ?: return addDeclaration(classOrObject, declaration)
        ensureSemicolonIsPresentAfterEnumEntriesIfNecessaryForDeclaration(classOrObject, declaration)
        return getOrCreateBody(classOrObject).addBefore(declaration, anchorAfter) as T
    }

    override fun getOrCreateBody(classOrObject: KtClassOrObject): KtClassBody {
        classOrObject.getBody()?.let { return it }

        val newBody = KtPsiFactory(classOrObject.project).createEmptyClassBody()
        return if (classOrObject is KtEnumEntry) {
            classOrObject.addAfter(newBody, classOrObject.initializerList ?: classOrObject.nameIdentifier) as KtClassBody
        } else {
            classOrObject.add(newBody) as KtClassBody
        }
    }

    private fun ensureSemicolonIsPresentAfterEnumEntriesIfNecessaryForDeclaration(
        classOrObject: KtClassOrObject,
        declaration: KtDeclaration,
    ) {
        if (declaration is KtEnumEntry) return
        if (classOrObject !is KtClass || !classOrObject.isEnum()) return

        val body = getOrCreateBody(classOrObject)
        val lastEnumEntry = body.children.filterIsInstance<KtEnumEntry>().lastOrNull()

        if (lastEnumEntry != null) {
            addSemicolon(lastEnumEntry)
        } else {
            val anchor = PsiTreeUtil.skipSiblingsBackward(body.rBrace ?: body.lastChild!!, PsiWhiteSpace::class.java)
            if (anchor != null && anchor.elementType == SEMICOLON) {
                // there's already a semicolon
                return
            }
            val psiFactory = KtPsiFactory(classOrObject.project)
            val semicolon = body.addAfter(psiFactory.createSemicolon(), anchor)
            if (anchor == body.lBrace) {
                body.addBefore(psiFactory.createNewLine(), semicolon)
            }
        }
    }

    override fun addSemicolon(enumEntry: KtEnumEntry): PsiElement {
        enumEntry.semicolon?.let {
            return it
        }

        // when adding a declaration to an enum class body, there's a chance the next
        // non-whitespace sibling is a semicolon; we should embed it into ourselves
        val tailStart = enumEntry.nextSibling
        val tailEnd = PsiTreeUtil.skipSiblingsForward(enumEntry, PsiWhiteSpace::class.java, PsiComment::class.java)
        if (tailEnd?.elementType == SEMICOLON) {
            var element = enumEntry.addRangeAfter(tailStart, tailEnd, enumEntry.lastChild)
            enumEntry.parent.deleteChildRange(tailStart, tailEnd)

            while (element.nextSibling != null) {
                element = element.nextSibling
            }

            return element
        }

        val semicolon = KtPsiFactory(enumEntry.project).createSemicolon()
        enumEntry.comma?.let {
            return it.replace(semicolon)
        }
        return enumEntry.addAfter(semicolon, enumEntry.lastChild)
    }

    override fun deleteSemicolon(element: KtElement) {
        if (element is KtEnumEntry) return

        val sibling = PsiTreeUtil.skipSiblingsForward(element, PsiWhiteSpace::class.java, PsiComment::class.java)
        if (sibling?.elementType != SEMICOLON) return

        val lastSiblingToDelete = PsiTreeUtil.skipSiblingsForward(sibling, PsiWhiteSpace::class.java)?.prevSibling ?: sibling
        element.parent.deleteChildRange(element.nextSibling, lastSiblingToDelete)
    }

    private fun deleteAsPlainKtElement(element: KtElement) {
        if (element is KtEnumEntry) return element.parent.deleteChildRange(element, element)

        val sibling = PsiTreeUtil.skipSiblingsForward(element, PsiWhiteSpace::class.java, PsiComment::class.java)
        if (sibling != null && sibling.elementType == SEMICOLON) {
            val lastSiblingToDelete = PsiTreeUtil.skipSiblingsForward(sibling, PsiWhiteSpace::class.java)?.prevSibling ?: sibling
            element.parent.deleteChildRange(element.nextSibling, lastSiblingToDelete)
        }

        element.parent.deleteChildRange(element, element)
    }
}
