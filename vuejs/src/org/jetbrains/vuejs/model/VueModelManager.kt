// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.model

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.javascript.web.js.WebJSResolveUtil
import com.intellij.lang.ecmascript6.psi.ES6ClassExpression
import com.intellij.lang.ecmascript6.psi.JSExportAssignment
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSElementTypes
import com.intellij.lang.javascript.JavaScriptBundle
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.ES6Decorator
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl
import com.intellij.lang.javascript.psi.util.JSStubBasedPsiTreeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.css.CssElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.asSafely
import com.intellij.xml.util.HtmlUtil
import com.intellij.xml.util.HtmlUtil.SCRIPT_TAG_NAME
import org.jetbrains.vuejs.codeInsight.findDefaultExport
import org.jetbrains.vuejs.codeInsight.getHostFile
import org.jetbrains.vuejs.codeInsight.toAsset
import org.jetbrains.vuejs.context.isVueContext
import org.jetbrains.vuejs.index.*
import org.jetbrains.vuejs.lang.html.VueFile
import org.jetbrains.vuejs.lang.html.isVueFile
import org.jetbrains.vuejs.model.VueModelManager.Companion.findEnclosingContainer
import org.jetbrains.vuejs.model.source.*
import org.jetbrains.vuejs.model.source.VueComponents.Companion.getComponentDescriptor
import org.jetbrains.vuejs.model.source.VueComponents.Companion.getSourceComponentDescriptor
import org.jetbrains.vuejs.model.typed.VueTypedEntitiesProvider
import org.jetbrains.vuejs.model.typed.VueTypedGlobal

class VueModelManager {

  companion object {

    fun getGlobal(context: PsiElement): VueGlobal =
      findEntitiesContainerContext(context).first()

    fun findEnclosingContainer(templateElement: PsiElement): VueEntitiesContainer =
      findEntitiesContainerContext(templateElement).second

    private fun findEntitiesContainerContext(context: PsiElement): Pair<() -> VueGlobal, VueEntitiesContainer> {
      fun find() =
        VueGlobalImpl.get(context).let { global ->
          val container = findComponent(context) as? VueEntitiesContainer ?: findVueApp(context, global) ?: global
          Pair({ container.source?.let { VueTypedGlobal(global, it) } ?: global }, container)
        }

      return if (ApplicationManager.getApplication().let { it.isDispatchThread && !it.isUnitTestMode })
        WebJSResolveUtil.disableIndexUpToDateCheckIn(context) {
          find()
        }
      else
        find()
    }

    /* This method is required in JS context. In TS context `this` type is resolved from the expected type handler. */
    fun findComponentForThisResolve(jsThisExpression: JSThisExpression): VueComponent? {
      //find enclosing function and it's second level enclosing function
      val function = PsiTreeUtil.getContextOfType(jsThisExpression, JSFunction::class.java)
      if (function == null || !isVueContext(function)) return null
      var secondLevelFunctionScope = PsiTreeUtil.findFirstContext(function, true) { it is JSFunction && !it.isArrowFunction } as? JSFunction
      if (function.isArrowFunction && secondLevelFunctionScope != null) {
        secondLevelFunctionScope = PsiTreeUtil.findFirstContext(secondLevelFunctionScope,
                                                                true) { it is JSFunction && !it.isArrowFunction } as? JSFunction
      }

      val component = findEnclosingComponent(jsThisExpression) ?: return null

      // we're good if function is part of implementation and second level function is null or is not part of the component
      if (component is VueSourceEntity
          && component.isPartOfImplementation(function)
          && (secondLevelFunctionScope == null || !component.isPartOfImplementation(secondLevelFunctionScope))) {
        return component
      }
      return null
    }

    fun findEnclosingComponent(jsElement: JSElement): VueComponent? {
      var context: PsiElement = PsiTreeUtil.getContextOfType(jsElement, false,
                                                             JSObjectLiteralExpression::class.java, JSClass::class.java,
                                                             JSEmbeddedContent::class.java)
                                ?: return null
      // Find the outermost JSObjectLiteralExpression or first enclosing class
      while (context is JSObjectLiteralExpression) {
        val superContext = PsiTreeUtil.getContextOfType(context, true,
                                                        JSObjectLiteralExpression::class.java, JSClass::class.java,
                                                        JSEmbeddedContent::class.java)
        if (superContext == null) {
          break
        }
        context = superContext
      }
      return getComponent(getSourceComponentDescriptor(context))
    }

    private fun findComponent(templateElement: PsiElement): VueComponent? {
      val baseElement: PsiElement? =
        if ((templateElement is JSElement || templateElement is CssElement || templateElement is PsiWhiteSpace) && templateElement.containingFile is XmlFile) {
          PsiTreeUtil.getParentOfType(templateElement, XmlElement::class.java)
        }
        else {
          templateElement
        }

      return when (baseElement) {
        is XmlElement -> InjectedLanguageManager.getInstance(baseElement.project)
                           .getInjectionHost(baseElement)
                           ?.let { it as? JSLiteralExpression }
                           ?.let { CompletionUtil.getOriginalOrSelf(it) }
                           ?.let { it.parent as? JSProperty }
                         ?: baseElement
        is JSElement -> InjectedLanguageManager.getInstance(templateElement.project).getInjectionHost(templateElement) as? XmlElement
        else -> null
      }
        ?.let { getEnclosingComponentDescriptor(CompletionUtil.getOriginalOrSelf(it)) }
        ?.let { getComponent(it) }
    }

    private fun getEnclosingComponentDescriptor(element: PsiElement): VueSourceEntityDescriptor? {
      var context: PsiElement? = element
      if (element is JSImplicitElement) {
        context = element.context
        if (context is JSCallExpression
            || (context is JSProperty && context.name != "name")) {
          val indexData = getVueIndexData(element)
          indexData
            ?.descriptorQualifiedReference
            ?.let { VueComponents.resolveReferenceToVueComponent(context!!, it) }
            ?.asSafely<VueSourceEntityDescriptor>()
            ?.let { return it }

          if (context is JSProperty) {
            if (indexData != null) {
              context.parent.asSafely<JSObjectLiteralExpression>()?.let {
                return VueSourceEntityDescriptor.tryCreate(it)
              }
            }
            return VueSourceEntityDescriptor.tryCreate(source = element)
          }
        }
      }
      if (context is JSCallExpression) {
        val stub = (context as? StubBasedPsiElement<*>)?.stub
        val initializer = if (stub != null) {
          stub.getChildrenByType(JSElementTypes.OBJECT_LITERAL_EXPRESSION,
                                 JSObjectLiteralExpression.ARRAY_FACTORY)
            .firstOrNull()
        }
        else {
          context.argumentList
            ?.arguments
            ?.find { it is JSObjectLiteralExpression }
        }.asSafely<JSObjectLiteralExpression>()
        return VueSourceEntityDescriptor.tryCreate(initializer, source = initializer ?: context)
      }
      else if (context is JSProperty) {
        context = context.context
      }

      if (context is JSObjectLiteralExpression) {
        val parentContext = PsiTreeUtil.getContextOfType(context, JSObjectLiteralExpression::class.java,
                                                         ES6Decorator::class.java)
        if (parentContext is ES6Decorator) {
          context = parentContext
        }
      }

      return when (context) {
        is JSObjectLiteralExpression -> VueSourceEntityDescriptor.tryCreate(initializer = context)
        is ES6Decorator -> {
          val clazz = when (val parentContext = context.context?.context) {
            is JSExportAssignment -> parentContext.stubSafeElement as? JSClass
            is JSClass -> parentContext
            else -> null
          }
          val obj = VueComponents.getDescriptorFromDecorator(context)
          if (clazz != null || obj != null)
            VueSourceEntityDescriptor.tryCreate(initializer = obj, clazz = clazz)
          else
            null
        }
        is JSClass -> {
          VueComponents.getClassComponentDescriptor(context)
        }
        null -> null
        else -> getDescriptorFromVueModule(context)
                ?: findReferencingComponentDescriptor(context)
      }
    }

    private fun findReferencingComponentDescriptor(context: PsiElement): VueSourceEntityDescriptor? {
      if (context !is XmlElement) return null

      PsiTreeUtil.findFirstParent(context) {
        (it as? XmlTag)?.let { tag ->
          tag.name == SCRIPT_TAG_NAME
          && tag.getAttribute("type")?.value == "text/x-template"
        } ?: false
      }?.let { scriptTag ->
        val id = (scriptTag as XmlTag).getAttribute("id")?.value ?: return null

        var result: VueSourceEntityDescriptor? = null
        StubIndex.getInstance().processElements(VUE_ID_INDEX_KEY, id, context.project,
                                                GlobalSearchScope.projectScope(context.project),
                                                PsiElement::class.java) { element ->
          if ((element as? JSProperty)?.indexingData
              ?.implicitElements
              ?.any {
                it.userString == VUE_ID_INDEX_JS_KEY
                && it?.qualifiedName == id
                && it.isValid
              } == true) {
            result = getEnclosingComponentDescriptor(element)
          }
          true
        }
        return result
      }

      val file = getHostFile(context) ?: return null
      val originalFile = context.containingFile.originalFile
      val name = file.viewProvider.virtualFile.name
      var result: VueSourceEntityDescriptor? = null

      StubIndex.getInstance().processElements(VUE_URL_INDEX_KEY, name, context.project,
                                              GlobalSearchScope.projectScope(context.project),
                                              PsiElement::class.java) { element ->
        if (element is JSImplicitElementProvider) {
          if (element.indexingData?.implicitElements
              ?.any {
                it.userString == VUE_URL_INDEX_JS_KEY
                && it?.qualifiedName == name
                && it.isValid
              } == true) {
            val candidate = getEnclosingComponentDescriptor(element)
            if (candidate != null
                && VueSourceContainer.getTemplate(candidate)
                  ?.source
                  ?.containingFile == file) {
              result = candidate
            }
          }
        }
        else if (element is XmlAttribute
                 && element.parent?.name == HtmlUtil.TEMPLATE_TAG_NAME
                 && element.valueElement?.references
                   ?.any { it.resolve()?.containingFile == originalFile } == true) {
          result = getDescriptorFromVueModule(element)
        }
        true
      }
      return result
    }

    private fun getDescriptorFromVueModule(element: PsiElement): VueSourceEntityDescriptor? {
      val file = element.containingFile?.originalFile
                   ?.asSafely<VueFile>()
                   ?.takeIf { it.isVueFile }
                 ?: return null
      val scriptModule = findModule(file, false)
      if (scriptModule != null) {
        findDefaultExport(scriptModule)
          ?.let { getComponentDescriptor(it) }
          ?.asSafely<VueSourceEntityDescriptor>()
          ?.let { return it }
      }
      return VueSourceEntityDescriptor.tryCreate(source = file)
    }

    private fun findVueApp(templateElement: PsiElement, global: VueGlobal): VueApp? {
      val xmlElement =
        if (templateElement is XmlElement) {
          templateElement
        }
        else {
          when (val context = PsiTreeUtil.getContextOfType(templateElement, PsiFile::class.java, XmlElement::class.java)) {
            is XmlElement -> context
            is PsiFile -> InjectedLanguageManager.getInstance(templateElement.project).getInjectionHost(context)
            else -> return null
          }
        } ?: return null

      val element2app = global.apps.associateBy { it.element }
      return xmlElement
        .parents(true)
        .filterIsInstance<XmlTag>()
        .firstNotNullOfOrNull { parent ->
          element2app[parent.localName]
          ?: element2app[parent.getAttributeValue("id")?.let { "#$it" }]
        }
    }

    /**
     * Warning: can return not-null but wrong VueComponent when called with PsiFile
     *
     * @see findEnclosingContainer
     */
    fun getComponent(element: PsiElement): VueComponent? =
      getComponent(getComponentDescriptor(element) ?: getEnclosingComponentDescriptor(element))

    fun getComponent(descriptor: VueEntityDescriptor?): VueComponent? =
      VueTypedEntitiesProvider.getComponent(descriptor)
      ?: (descriptor as? VueSourceEntityDescriptor)?.getCachedValue { descr ->
        val declaration = descr.source
        val implicitElement = getComponentImplicitElement(declaration)
        val data = implicitElement
          ?.let { getVueIndexData(it) }
          ?.takeIf { it.originalName != JavaScriptBundle.message("element.name.anonymous") }
        CachedValueProvider.Result.create(VueSourceComponent(implicitElement
                                                             ?: buildImplicitElement(declaration),
                                                             descr, data), declaration)
      }

    fun getMixin(mixin: PsiElement): VueMixin? =
      getMixin(getSourceComponentDescriptor(mixin) ?: getEnclosingComponentDescriptor(mixin))

    fun getMixin(descriptor: VueSourceEntityDescriptor?): VueMixin? =
      descriptor?.getCachedValue {
        val declaration = it.source
        CachedValueProvider.Result.create(VueSourceMixin(getComponentImplicitElement(declaration)
                                                         ?: buildImplicitElement(declaration),
                                                         it), declaration)
      }

    fun getFilter(implicitElement: JSImplicitElement): VueFilter? {
      if (implicitElement.userString == VUE_GLOBAL_FILTERS_INDEX_JS_KEY) {
        val call = implicitElement.context ?: return null
        var filterMethod: PsiElement = implicitElement
        val functionReference = getVueIndexData(implicitElement)?.descriptorQualifiedReference
        if (!functionReference.isNullOrBlank()) {
          val resolved = resolveFilterReference(call, functionReference)
          if (resolved != null) {
            filterMethod = resolved
          }
        }
        else if (call is JSCallExpression) {
          val stub = (call as? StubBasedPsiElement<*>)?.stub
          if (stub != null) {
            stub.childrenStubs.asSequence()
              .filter { it.elementType !== JSElementTypes.LITERAL_EXPRESSION }
              .firstOrNull()
              ?.let { filterMethod = it.psi }
          }
          else {
            call.arguments.getOrNull(1)
              ?.let { filterMethod = it }
          }
        }
        return VueSourceFilter(toAsset(implicitElement.name), filterMethod)
      }
      return null
    }

    private fun resolveFilterReference(context: PsiElement, refName: String): PsiElement? {
      val localRes = JSStubBasedPsiTreeUtil.resolveLocally(refName, context)
                     ?: return null
      return JSStubBasedPsiTreeUtil.calculateMeaningfulElements(localRes)
               .firstOrNull()
             ?: localRes
    }

    fun getApp(appDeclaration: JSObjectLiteralExpression): VueApp {
      return CachedValuesManager.getCachedValue(appDeclaration) {
        val app = VueSourceApp(
          getComponentImplicitElement(appDeclaration) ?: buildImplicitElement(appDeclaration),
          appDeclaration
        )
        CachedValueProvider.Result.create(app, appDeclaration)
      }
    }

    private fun getComponentImplicitElement(declaration: PsiElement): JSImplicitElement? =
      if (declaration is JSImplicitElement && declaration.userString == VUE_COMPONENTS_INDEX_JS_KEY)
        declaration
      else if (declaration is JSObjectLiteralExpression) {
        val implicitElement = declaration.findProperty("name")
                                ?.let { getComponentImplicitElement(it) as? JSImplicitElementImpl }
                              ?: declaration.firstProperty
                                ?.let { buildImplicitElement(it) }
        var parent = PsiTreeUtil.getContextOfType(declaration, ES6ClassExpression::class.java, JSExportAssignment::class.java)
        if (parent is JSExportAssignment) {
          parent = parent.stubSafeElement as? JSClass
        }
        if (parent is JSClass)
          (implicitElement?.toBuilder() ?: JSImplicitElementImpl.Builder(
            JavaScriptBundle.message("element.name.anonymous"), parent))
            .setProvider(parent)
            .forbidAstAccess()
            .toImplicitElement()
        else
          implicitElement
      }
      else if (declaration is JSClass)
        buildImplicitElement(declaration, declaration.name ?: JavaScriptBundle.message("element.name.anonymous"))
      else
        (declaration as? JSImplicitElementProvider)?.indexingData?.implicitElements?.find {
          it.userString == VUE_COMPONENTS_INDEX_JS_KEY
        }


    private fun buildImplicitElement(parent: PsiElement, name: String = JavaScriptBundle.message("element.name.anonymous")) =
      JSImplicitElementImpl(JSImplicitElementImpl.Builder(name, parent).forbidAstAccess())

  }
}
