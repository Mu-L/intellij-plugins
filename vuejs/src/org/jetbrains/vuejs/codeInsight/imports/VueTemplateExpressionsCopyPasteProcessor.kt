// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.codeInsight.imports

import com.intellij.javascript.web.js.WebJSResolveUtil.disableIndexUpToDateCheckIn
import com.intellij.lang.Language
import com.intellij.lang.ecmascript6.editor.ES6CopyPasteProcessorBase
import com.intellij.lang.ecmascript6.psi.impl.ES6CreateImportUtil
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.ecmascript6.refactoring.ES6ReferenceExpressionsInfo
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.editor.JSCopyPasteService
import com.intellij.lang.javascript.psi.JSElement
import com.intellij.lang.javascript.psi.JSExecutionScope
import com.intellij.lang.javascript.psi.JSRecursiveWalkingElementVisitor
import com.intellij.lang.javascript.psi.impl.JSEmbeddedContentImpl
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.lang.javascript.settings.JSApplicationSettings
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlRecursiveElementWalkingVisitor
import com.intellij.psi.createSmartPointer
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import com.intellij.psi.util.parentOfTypes
import com.intellij.psi.util.parents
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.asSafely
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import org.jetbrains.vuejs.VueBundle
import org.jetbrains.vuejs.codeInsight.imports.VueTemplateExpressionsCopyPasteProcessor.VueTemplateExpressionsImportsTransferableData
import org.jetbrains.vuejs.editor.VueComponentSourceEdit
import org.jetbrains.vuejs.index.findScriptTag
import org.jetbrains.vuejs.lang.LangMode
import org.jetbrains.vuejs.lang.expr.VueJSLanguage
import org.jetbrains.vuejs.lang.html.VueFile
import org.jetbrains.vuejs.model.VueContainer
import org.jetbrains.vuejs.model.VueModelManager
import org.jetbrains.vuejs.model.VueModelVisitor
import org.jetbrains.vuejs.model.VueProperty
import java.awt.datatransfer.DataFlavor

class VueTemplateExpressionsCopyPasteProcessor : ES6CopyPasteProcessorBase<VueTemplateExpressionsImportsTransferableData>() {

  override val dataFlavor: DataFlavor
    get() = VUE_TEMPLATE_EXPRESSIONS_IMPORTS_FLAVOR

  override fun isAcceptableCopyContext(file: PsiFile, contextElements: List<PsiElement>): Boolean {
    val settings = JSApplicationSettings.getInstance()
    return file is VueFile
           && file.langMode
             .let {
               (it == LangMode.HAS_TS && settings.isUseTypeScriptAutoImport)
               || (it != LangMode.HAS_TS && settings.isUseJavaScriptAutoImport)
             }
           // TODO support import for props/methods in case of options and class based components
           && findScriptTag(file, true) != null
  }

  override fun isAcceptablePasteContext(context: PsiElement): Boolean =
    context.containingFile is VueFile
    && context.parentOfTypes(JSExecutionScope::class, XmlTag::class, PsiFile::class, withSelf = true)
      .let { (it !is JSExecutionScope || it is XmlElement) && it != null }

  override fun hasUnsupportedContentInCopyContext(parent: PsiElement, textRange: TextRange): Boolean {
    var result = false
    parent.accept(object : JSRecursiveWalkingElementVisitor() {
      override fun visitJSElement(node: JSElement) {
        if (node is JSEmbeddedContentImpl && textRange.intersects(node.textRange)) {
          result = true
          stopWalking()
        }
      }
    })
    return result || parent.parents(true).any { it is JSEmbeddedContentImpl }
  }

  override fun collectReferenceExpressions(parent: PsiElement, range: TextRange, addInfo: (ES6ReferenceExpressionsInfo) -> Unit): Boolean {
    if (!super.collectReferenceExpressions(parent, range, addInfo)) return false
    // We need to collect injected Vue JS expressions
    val injectedLanguageManager = InjectedLanguageManager.getInstance(parent.project)
    parent.acceptChildren(object : XmlRecursiveElementWalkingVisitor() {
      override fun visitXmlText(text: XmlText) {
        injectedLanguageManager.enumerate(text) { injectedPsi, _ ->

          @Suppress("DEPRECATION")
          val injectionWindow = InjectedLanguageUtil.getDocumentWindow(injectedPsi)

          @Suppress("DEPRECATION")
          val adjustedRange = if (injectionWindow != null)
            TextRange(InjectedLanguageUtil.hostToInjectedUnescaped(injectionWindow, range.startOffset),
                      InjectedLanguageUtil.hostToInjectedUnescaped(injectionWindow, range.endOffset))
          else
            range

          addInfo(ES6ReferenceExpressionsInfo.getInfo(injectedPsi, adjustedRange))
        }
      }
    })
    return true
  }

  override fun processTransferableData(
    values: List<VueTemplateExpressionsImportsTransferableData>,
    exportScope: PsiElement,
    pasteContext: PsiElement,
    pasteContextLanguage: Language,
  ) {
    if (pasteContext.containingFile.asSafely<XmlFile>()?.let { findScriptTag(it, true) } != null)
      super.processTransferableData(values, exportScope, pasteContext, pasteContextLanguage)
    else {
      processTransferableDataNoScriptTag(values, exportScope, pasteContext)
    }
  }

  private fun processTransferableDataNoScriptTag(
    values: List<VueTemplateExpressionsImportsTransferableData>,
    exportScope: PsiElement,
    pasteContext: PsiElement,
  ) {
    val exportScopePtr = exportScope.createSmartPointer()
    pasteContext.project.service<JSCopyPasteService>().scheduleOnPasteProcessing(
      pasteContext.containingFile,
      VueBundle.message("vue.progress.title.auto-importing-external-symbols-on-paste"),
      VueBundle.message("vue.command.name.auto-import-external-symbols"),
      {
        val newExportScope = exportScopePtr.dereference() ?: return@scheduleOnPasteProcessing emptyList()
        val resolveScope = JSResolveUtil.getResolveScope(newExportScope)
        runBlockingCancellable {
          values.map { it.importedElementsDeferred }.awaitAll()
        }.flatMap { data ->
          data
            .mapNotNull { importedElement ->
              resolveImportedElement(importedElement, newExportScope, resolveScope)
                ?.let { Pair(it.first, it.second) }
            }
        }
      }, { elements ->
        val newExportScope = exportScopePtr.dereference() ?: return@scheduleOnPasteProcessing
        ES6CreateImportUtil.addRequiredImports(
          newExportScope, VueJSLanguage.INSTANCE, elements.mapNotNull { it.second?.let { el -> Pair(it.first, el) } })
      }
    )

  }

  override fun alreadyHasImport(actualImportedName: String, importedElement: ImportedElement, scope: PsiElement): Boolean =
    if (scope.containingFile.asSafely<XmlFile>()?.let { findScriptTag(it, true) } != null)
      super.alreadyHasImport(actualImportedName, importedElement, scope)
    else {
      var result = false
      val container = VueModelManager.findEnclosingContainer(scope) as? VueContainer
      container?.acceptPropertiesAndMethods(object : VueModelVisitor() {
        override fun visitProperty(property: VueProperty, proximity: Proximity): Boolean {
          result = result || property.name == actualImportedName
          return !result
        }
      }, false)
      result
    }

  override fun createTransferableData(importedElementsDeferred: Deferred<List<ImportedElement>>): VueTemplateExpressionsImportsTransferableData =
    VueTemplateExpressionsImportsTransferableData(importedElementsDeferred)

  override fun getExportScope(file: PsiFile, caret: Int): PsiElement? {
    return super.getExportScope(file, caret)
           ?: WriteAction.compute<PsiElement, Throwable> {
             VueComponentSourceEdit.getOrCreateScriptScope(
               disableIndexUpToDateCheckIn(file) { VueModelManager.findEnclosingContainer(file) })
           }
  }

  override fun insertRequiredImports(
    pasteContext: PsiElement,
    data: VueTemplateExpressionsImportsTransferableData,
    destinationModule: PsiElement,
    imports: Collection<Pair<ES6ImportPsiUtil.CreateImportExportInfo, PsiElement>>,
    pasteContextLanguage: Language,
  ) {
    ES6CreateImportUtil.addRequiredImports(destinationModule, VueJSLanguage.INSTANCE, imports)
  }

  class VueTemplateExpressionsImportsTransferableData(importedElementsDeferred: Deferred<List<ImportedElement>>) : ES6ImportsTransferableDataBase(importedElementsDeferred) {
    override fun getFlavor(): DataFlavor {
      return VUE_TEMPLATE_EXPRESSIONS_IMPORTS_FLAVOR
    }
  }

}

private val VUE_TEMPLATE_EXPRESSIONS_IMPORTS_FLAVOR = DataFlavor(VueTemplateExpressionsImportsTransferableData::class.java,
                                                                 "vue es6 imports")