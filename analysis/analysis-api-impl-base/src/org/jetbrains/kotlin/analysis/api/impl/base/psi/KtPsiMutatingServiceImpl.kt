/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.psi

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.CheckUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.siblings

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

    override fun removeSuperTypeListEntry(declaration: KtClassOrObject, superTypeListEntry: KtSuperTypeListEntry) {
        val specifierList = declaration.getSuperTypeList() ?: return
        assert(superTypeListEntry.parent === specifierList)

        if (specifierList.entries.size > 1) {
            EditCommaSeparatedListHelper.removeItem<KtElement>(superTypeListEntry)
        } else {
            declaration.deleteChildRange(declaration.getColon() ?: specifierList, specifierList)
        }
    }

    override fun deleteClassOrObject(declaration: KtClassOrObject) {
        CheckUtil.checkWritable(declaration)

        val file = declaration.containingKtFile
        if (!declaration.isTopLevel() || file.declarations.size > 1) {
            deleteAsPlainKtElement(declaration)
        } else {
            file.delete()
        }
    }

    override fun setModifierList(owner: KtModifierListOwner, newModifierList: KtModifierList) {
        val currentModifierList = owner.modifierList
        if (currentModifierList != null) {
            currentModifierList.replace(newModifierList)
        } else {
            owner.addModifierList(newModifierList)
        }
    }

    override fun addModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken) {
        val modifierList = owner.modifierList
        if (modifierList == null) {
            createModifierList(modifier.value, owner)
        } else {
            addModifier(modifierList, modifier)
        }
    }

    override fun addConstructorModifier(constructor: KtPrimaryConstructor, modifier: KtModifierKeywordToken) {
        val modifierList = constructor.modifierList
        if (modifierList != null) {
            addModifier(modifierList, modifier)
            if (constructor.modifierList == null) {
                constructor.getConstructorKeyword()?.delete()
            }
        } else {
            if (modifier == PUBLIC_KEYWORD) return
            val newModifierList = KtPsiFactory(constructor.project).createModifierList(modifier)
            constructor.addBefore(newModifierList, getOrCreateConstructorKeyword(constructor))
        }
    }

    override fun removeModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken) {
        doRemoveModifier(owner, modifier)
    }

    override fun removeConstructorModifier(constructor: KtPrimaryConstructor, modifier: KtModifierKeywordToken) {
        doRemoveModifier(constructor, modifier)
        if (constructor.modifierList == null) {
            removeRedundantConstructorKeywordAndSpace(constructor)
        }
    }

    override fun addAnnotationEntry(owner: KtModifierListOwner, annotationEntry: KtAnnotationEntry): KtAnnotationEntry {
        val modifierList = owner.modifierList
        return if (modifierList == null) {
            createModifierList(annotationEntry.text, owner).annotationEntries.first()
        } else {
            modifierList.addBefore(annotationEntry, modifierList.firstChild) as KtAnnotationEntry
        }
    }

    override fun addConstructorAnnotationEntry(constructor: KtPrimaryConstructor, annotationEntry: KtAnnotationEntry): KtAnnotationEntry {
        val modifierList = constructor.modifierList
        return if (modifierList != null) {
            modifierList.addBefore(annotationEntry, modifierList.firstChild) as KtAnnotationEntry
        } else {
            val newModifierList = KtPsiFactory(constructor.project).createModifierList(annotationEntry.text)
            val addedModifierList = constructor.addBefore(newModifierList, getOrCreateConstructorKeyword(constructor)) as KtModifierList
            addedModifierList.annotationEntries.first()
        }
    }

    override fun removeRedundantConstructorKeywordAndSpace(constructor: KtPrimaryConstructor) {
        constructor.getConstructorKeyword()?.delete()
        if (constructor.prevSibling is PsiWhiteSpace) {
            constructor.prevSibling.delete()
        }
    }

    override fun replaceImplicitDelegationCallWithExplicit(
        constructor: KtSecondaryConstructor,
        isThis: Boolean,
    ): KtConstructorDelegationCall = with(constructor) {
        val psiFactory = KtPsiFactory(project)
        val current = getDelegationCall()

        assert(current.isImplicit) { "Method should not be called with explicit delegation call: $text" }
        current.delete()

        val colon = addAfter(psiFactory.createColon(), valueParameterList)
        val delegationName = if (isThis) "this" else "super"

        addAfter(psiFactory.creareDelegatedSuperTypeEntry("$delegationName()"), colon) as KtConstructorDelegationCall
    }

    private fun getOrCreateConstructorKeyword(constructor: KtPrimaryConstructor): PsiElement {
        return constructor.getConstructorKeyword()
            ?: constructor.addBefore(KtPsiFactory(constructor.project).createConstructorKeyword(), constructor.valueParameterList!!)
    }

    private fun KtModifierListOwner.addModifierList(newModifierList: KtModifierList): KtModifierList {
        val anchor = firstChild!!
            .siblings(forward = true)
            .dropWhile { it is PsiComment || it is PsiWhiteSpace || it is KtContextParameterList }
            .first()
        return addBefore(newModifierList, anchor) as KtModifierList
    }

    private fun createModifierList(text: String, owner: KtModifierListOwner): KtModifierList {
        return owner.addModifierList(KtPsiFactory(owner.project).createModifierList(text))
    }

    private fun addModifier(modifierList: KtModifierList, modifier: KtModifierKeywordToken) {
        if (modifierList.hasModifier(modifier)) return

        val newModifier = KtPsiFactory(modifierList.project).createModifier(modifier)
        val modifierToReplace = MODIFIERS_TO_REPLACE[modifier]
            ?.firstNotNullOfOrNull(modifierList::getModifier)

        if (modifier == FINAL_KEYWORD && !modifierList.hasModifier(OVERRIDE_KEYWORD)) {
            if (modifierToReplace != null) {
                modifierToReplace.delete()
                if (modifierList.firstChild == null) {
                    modifierList.delete()
                }
            }
            return
        }

        if (modifierToReplace != null && modifierList.firstChild == modifierList.lastChild) {
            modifierToReplace.replace(newModifier)
        } else {
            modifierToReplace?.delete()
            val newModifierOrder = MODIFIER_KEYWORDS_ARRAY.indexOf(modifier)

            fun placeAfter(child: PsiElement): Boolean {
                if (child is PsiWhiteSpace) return false
                if (child is KtAnnotation || child is KtAnnotationEntry) return true
                val order = MODIFIER_KEYWORDS_ARRAY.indexOf(child.node.elementType)
                return newModifierOrder > order
            }

            val lastChild = modifierList.lastChild
            val anchor = lastChild?.siblings(forward = false)?.firstOrNull(::placeAfter).let {
                when {
                    it?.nextSibling is PsiWhiteSpace &&
                            (it is KtAnnotation || it is KtAnnotationEntry || it is KtContextParameterList || it is PsiComment) -> it.nextSibling
                    it == null && modifierList.firstChild is PsiWhiteSpace -> modifierList.firstChild
                    else -> it
                }
            }
            modifierList.addAfter(newModifier, anchor)

            if (anchor == lastChild) {
                val whiteSpace = modifierList.nextSibling as? PsiWhiteSpace
                if (whiteSpace != null && whiteSpace.text.contains('\n')) {
                    modifierList.addAfter(whiteSpace, anchor)
                    whiteSpace.delete()
                }
            }
        }
    }

    private fun doRemoveModifier(owner: KtModifierListOwner, modifier: KtModifierKeywordToken) {
        val modifierList = owner.modifierList ?: return
        val modifierElement = modifierList.getModifier(modifier)
        if (modifierElement != null) {
            val forward = modifierList.lastChild != modifierElement
            val rangeEnd = modifierElement.siblings(forward = forward, withItself = true)
                .takeWhile { it is PsiWhiteSpace || it == modifierElement }
                .last()

            if (forward) {
                modifierList.deleteChildRange(modifierElement, rangeEnd)
            } else {
                modifierList.deleteChildRange(rangeEnd, modifierElement)
            }
        }

        if (modifierList.firstChild == null) {
            val rangeEnd = modifierList.siblings(forward = true, withItself = true)
                .takeWhile { it is PsiWhiteSpace || it == modifierList }
                .last()
            owner.deleteChildRange(modifierList, rangeEnd)
            return
        }

        val lastChild = modifierList.lastChild
        if (lastChild is PsiComment) {
            modifierList.addAfter(KtPsiFactory(owner.project).createNewLine(), lastChild)
        }
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

    private companion object {
        val MODIFIERS_TO_REPLACE = mapOf(
            OVERRIDE_KEYWORD to listOf(OPEN_KEYWORD),
            ABSTRACT_KEYWORD to listOf(OPEN_KEYWORD, FINAL_KEYWORD),
            OPEN_KEYWORD to listOf(FINAL_KEYWORD, ABSTRACT_KEYWORD),
            FINAL_KEYWORD to listOf(ABSTRACT_KEYWORD, OPEN_KEYWORD),
            PUBLIC_KEYWORD to listOf(PROTECTED_KEYWORD, PRIVATE_KEYWORD, INTERNAL_KEYWORD),
            PROTECTED_KEYWORD to listOf(PUBLIC_KEYWORD, PRIVATE_KEYWORD, INTERNAL_KEYWORD),
            PRIVATE_KEYWORD to listOf(PUBLIC_KEYWORD, PROTECTED_KEYWORD, INTERNAL_KEYWORD),
            INTERNAL_KEYWORD to listOf(PUBLIC_KEYWORD, PROTECTED_KEYWORD, PRIVATE_KEYWORD),
            EXPECT_KEYWORD to listOf(ACTUAL_KEYWORD),
            ACTUAL_KEYWORD to listOf(EXPECT_KEYWORD),
        )
    }
}
