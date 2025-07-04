// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.lang

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.javascript.testFramework.web.filterOutStandardHtmlSymbols
import com.intellij.javascript.testFramework.web.forceReloadProjectRoots
import com.intellij.lang.ecmascript6.psi.impl.JSImportsCoroutineScope
import com.intellij.lang.javascript.BaseJSCompletionTestCase.*
import com.intellij.lang.javascript.JSTestUtils
import com.intellij.lang.javascript.JavaScriptFormatterTestBase
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.lang.javascript.TypeScriptTestUtil
import com.intellij.lang.javascript.completion.JSLookupPriority
import com.intellij.lang.javascript.formatter.JSCodeStyleSettings
import com.intellij.lang.javascript.settings.JSApplicationSettings
import com.intellij.lang.javascript.typeWithWaitCoroutinesBlocking
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlAttribute
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TestLookupElementPresentation.renderReal
import com.intellij.util.containers.ContainerUtil
import com.intellij.polySymbols.testFramework.LookupElementInfo
import com.intellij.polySymbols.testFramework.and
import com.intellij.polySymbols.testFramework.checkListByFile
import com.intellij.polySymbols.testFramework.enableIdempotenceChecksOnEveryCache
import com.intellij.polySymbols.testFramework.moveToOffsetBySignature
import com.intellij.polySymbols.testFramework.noAutoComplete
import com.intellij.polySymbols.testFramework.renderLookupItems
import com.intellij.testFramework.utils.coroutines.waitCoroutinesBlocking
import com.intellij.workspaceModel.ide.impl.WorkspaceEntityLifecycleSupporterUtils
import junit.framework.ComparisonFailure
import junit.framework.TestCase
import org.jetbrains.vuejs.codeInsight.toAsset
import org.jetbrains.vuejs.libraries.VUE_CLASS_COMPONENT

class VueCompletionTest : BasePlatformTestCase() {
  override fun getTestDataPath(): String = getVueTestDataPath() + "/completion/"

  override fun setUp() {
    super.setUp()
    // Let's ensure we don't get PolySymbols registry stack overflows randomly
    this.enableIdempotenceChecksOnEveryCache()
  }

  fun testCompleteCssClasses() {
    myFixture.configureByText("a.css", ".externalClass {}")
    myFixture.configureByText("a.vue", "<template><div class=\"<caret>\"></div></template><style>.internalClass</style>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "externalClass", "internalClass")
  }


  fun testCompleteAttributesWithVueInNodeModules() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("package.json", "{}")
    myFixture.configureByText("index.html", "<html <caret>></html>")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "v-bind", "v-else")
  }

  fun testCompleteAttributesWithVueInPackageJson() {
    myFixture.configureVueDependencies()
    myFixture.configureByText("index.html", "<html <caret>></html>")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "v-bind", "v-else")
  }

  fun testNoVueCompletionWithoutVue() {
    myFixture.configureByText("a.js", "")
    myFixture.configureByText("index.html", "<html <caret>></html>")
    myFixture.completeBasic()
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, "v-bind", "v-else", ":class")
  }

  fun testCompleteImportedComponent() {
    myFixture.configureByText("compUI.vue", """
<template>{{ strangeCase }}</template>
<script>
  export default {
    props: ['strangeCase']
  }
</script>
""")
    myFixture.configureByText("CompleteImportedComponent.vue", """
<template>
<co<caret>
</template>
<script>
import compUI from 'compUI.vue'
</script>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "comp-u-i")
  }

  fun testCompleteWithImport() {
    configureTextsForCompleteLocalComponent()

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "to-import")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<to-import<caret>
</template>
<script>
import ToImport from "./toImport.vue";

export default {
  components: {ToImport}
}
</script>
""")
    }
  }


  fun testCompleteWithImportNoExtension() {
    JavaScriptFormatterTestBase.setTempSettings<JSCodeStyleSettings>(project, JavascriptLanguage) {
      it.USE_EXPLICIT_JS_EXTENSION = JSCodeStyleSettings.UseExplicitExtension.NEVER
    }
    configureTextsForCompleteLocalComponent()

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "to-import")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<to-import<caret>
</template>
<script>
import ToImport from "./toImport";

export default {
  components: {ToImport}
}
</script>
""")
    }
  }

  private fun configureTextsForCompleteLocalComponent(tsLang: Boolean = false) {
    myFixture.configureByText("toImport.vue", """
<template>text here</template>
<script>
export default {
  name: 'toImport',
  props: ['strangeCase']
}
</script>
""")
    myFixture.configureByText(getTestName(false) + ".vue", """
<template>
<to<caret>
</template>
<script${if (tsLang) " lang=\"ts\"" else ""}>
export default {
}
</script>
""")
  }

  fun testCompleteNoImportIfSettingIsOffJs() {
    configureTextsForCompleteLocalComponent()
    val jsApplicationSettings = JSApplicationSettings.getInstance()
    val before = jsApplicationSettings.isUseJavaScriptAutoImport
    jsApplicationSettings.isUseJavaScriptAutoImport = false
    try {
      noAutoComplete {
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings!!, "to-import")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("""
<template>
<to-import<caret>
</template>
<script>
export default {
}
</script>
""")
      }
    }
    finally {
      jsApplicationSettings.isUseJavaScriptAutoImport = before
    }
  }

  fun testCompleteNoImportIfSettingIsOffTs() {
    configureTextsForCompleteLocalComponent(true)
    val jsApplicationSettings = JSApplicationSettings.getInstance()
    val before = jsApplicationSettings.isUseTypeScriptAutoImport
    jsApplicationSettings.isUseTypeScriptAutoImport = false
    try {
      noAutoComplete {
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings!!, "to-import")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("""
<template>
<to-import<caret>
</template>
<script lang="ts">
export default {
}
</script>
""")
      }
    }
    finally {
      jsApplicationSettings.isUseTypeScriptAutoImport = before
    }
  }

  fun testCompleteWithImportCreateExport() {
    myFixture.configureByText("toImport.vue", """
<script>
export default {
  name: 'toImport'
}
</script>
""")
    myFixture.configureByText("CompleteWithImportCreateExport.vue", """
<template>
<To<caret>></To>
</template>
<script>
</script>
""")

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "ToImport")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<ToImport<caret>></ToImport>
</template>
<script>
import {defineComponent} from "vue";
import ToImport from "./toImport.vue";

export default defineComponent({
  components: {ToImport}
})
</script>
""")
    }
  }

  fun testCompleteWithImportCreateScript() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10)
    myFixture.configureByText("toImport.vue", """
<script>
export default {
  name: 'toBeImported'
}
</script>
""")
    myFixture.configureByText("CompleteWithImportCreateScript.vue", """
<template>
<to<caret>
</template>
""")

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "to-be-imported")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<to-be-imported
</template>
<script>
import ToBeImported from "./toImport.vue";

export default {
  components: {ToBeImported}
}
</script>""")
    }
  }

  fun testCompleteWithImportCreateScriptNoExport() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10)
    myFixture.configureByText("toImport.vue", """
""")
    myFixture.configureByText("CompleteWithImportCreateScript.vue", """
<template>
<to<caret>
</template>
""")

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "to-import")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<to-import
</template>
<script>
import ToImport from "./toImport.vue";

export default {
  components: {ToImport}
}
</script>""")
    }
  }

  fun testCompleteWithoutImportForRenamedGlobalComponent() {
    myFixture.configureByText("libComponent.vue", """
<template>text here</template>
<script>
  export default {
    name: 'libComponent',
    props: ['strangeCase']
  }
</script>
""")
    myFixture.configureByText("main.js", """
import LibComponent from "./libComponent"
Vue.component('renamed-component', LibComponent)
""")
    myFixture.configureByText("CompleteWithoutImportForRenamedGlobalComponent.vue", """
<template>
<ren<caret>
</template>
<script>
export default {
}
</script>
""")

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "renamed-component")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<renamed-component<caret>
</template>
<script>
export default {
}
</script>
""")
    }
  }

  fun testCompleteWithoutImportForGlobalComponent() {
    myFixture.configureByText("lib2Component.vue", """
<template>text here</template>
<script>
  export default {
    name: 'lib2Component',
    props: ['strangeCase']
  }
</script>
""")
    myFixture.configureByText("main.js", """
import Lib2Component from "./lib2Component"
Vue.component('lib2-component', LibComponent)
""")
    myFixture.configureByText("CompleteWithoutImportForGlobalComponent.vue", """
<template>
<lib<caret>
</template>
<script>
export default {
}
</script>
""")

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "lib2-component")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
<template>
<lib2-component<caret>
</template>
<script>
export default {
}
</script>
""")
    }
  }

  fun testCompleteAttributesFromProps() {
    myFixture.configureByText("compUI.vue", """
<template>{{ strangeCase }}</template>
<script>
  export default {
    props: ['strangeCase']
  }
</script>
""")
    myFixture.configureByText("CompleteAttributesFromProps.vue", """
<template>
<compUI <caret>></compUI>
</template>
<script>
import compUI from 'compUI.vue'

export default {
  components: {compUI}
}
</script>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "strange-case")
  }

  fun testCompletePropsInInterpolation() {
    myFixture.configureByText("CompletePropsInInterpolation.vue", """
<template>
{{<caret>}}
</template>
<script>
export default {
  name: 'childComp',
  props: {'myMessage': {}}
}
</script>""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "myMessage")
  }

  fun testCompleteComputedPropsInInterpolation() {
    myFixture.configureByText("CompleteComputedPropsInInterpolation.vue", """
<template>
{{<caret>}}
</template>
<script>
export default {
  name: 'childComp',
  props: {'MyMessage': {}},
  computed: {
    testWrong: 111,
    testRight: function() {}
  }
}
</script>""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "MyMessage", "testRight", "testWrong")
  }

  fun testCompleteMethodsInBoundAttributes() {
    myFixture.configureByText("child.vue", """
<template>
</template>
<script>
export default {
  name: 'childComp',
  props: {'myMessage': {}},
  methods: {
    reverseMessage() {
      return this.myMessage.reverse()
    }
  }
}
</script>""")
    myFixture.configureByText("CompleteMethodsInBoundAttributes.vue", """
<template>
    <child-comp v-bind:my-message="m<caret>"></child-comp>
</template>
<script>
import ChildComp from 'child.vue'
export default {
  components: {ChildComp},
  name: 'parent',
  methods: {
    me215thod: function () {
      return 'something!'
    }
  }
}
</script>""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "me215thod")
  }

  fun testCompleteElementsFromLocalData() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("CompleteElementsFromLocalData.vue", """
  <template>{{<caret>}}</template>
  <script>
  let props = ['parentMsg'];

  export default {
    name: 'parent',
    props: props,
    data: {
      groceryList: {}
    }
  }</script>""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "groceryList", "parentMsg")
  }

  fun testCompleteElementsFromLocalData2() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("CompleteElementsFromLocalData2.vue", """
    <template>{{<caret>}}</template>
    <script>
    let props = ['parentMsg'];

    export default {
      name: 'parent',
      props: props,
      data: () => {
                return {groceryList: 12}
              }
    }</script>""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "groceryList", "parentMsg", "\$props", "\$data")
  }

  fun testScrInStyleCompletion() {
    val excluded = arrayOf("exclude.xml", "exclude.txt")
    val included = arrayOf("foo/bar.xml", "a.pcss", "b.styl", "c.less", "d.sass", "e.scss", "f.css")

    excluded.forEach { myFixture.addFileToProject(it, "") }
    included.forEach { myFixture.addFileToProject(it, "") }

    val file = myFixture.addFileToProject("./ScrInStyleCompletion.vue", """<style src="./<caret>"></style>""")
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, included.map { it.substringBefore('/', it) })
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, excluded)
  }

  fun testScrInStyleCompletionWithLang() {
    val excluded = arrayOf("exclude.xml", "exclude.txt", "a.pcss", "c.less", "d.sass", "e.scss", "f.css")
    val included = arrayOf("foo/bar.xml", "b.styl")

    excluded.forEach { myFixture.addFileToProject(it, "") }
    included.forEach { myFixture.addFileToProject(it, "") }

    val file = myFixture.addFileToProject("./ScrInStyleCompletion.vue", """<style src="./<caret>" lang="stylus"></style>""")
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, included.map { it.substringBefore('/', it) })
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, excluded)
  }

  fun testInsertAttributeWithoutValue() {
    noAutoComplete {
      myFixture.configureByText("InsertAttributeWithoutValue.vue", "<template v-onc<caret>></template>")
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "v-once")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("<template v-once<caret>></template>")
    }
  }

  fun testInsertAttributeWithValue() {
    noAutoComplete {
      myFixture.configureByText("InsertAttributeWithValue.vue", "<template v-tex<caret>></template>")
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "v-text")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("<template v-text=\"<caret>\"></template>")
    }
  }

  fun testMixinsInCompletion() {
    myFixture.configureByText("index.js", globalMixinText())
    myFixture.configureByText("FirstMixin.vue", """
<script>
  export default {
    props: ['FirstMixinProp']
  }
</script>
""")
    myFixture.configureByText("SecondMixin.vue", """
<script>
  export default {
    props: ['SecondMixinProp']
  }
</script>
""")
    myFixture.configureByText("CompWithTwoMixins.vue", """
<template>
  <mixins-in-completion <caret>></mixins-in-completion>
</template>
<script>
  import FirstMixin from './FirstMixin';
  import SecondMixin from './SecondMixin';

  export default {
    name: 'mixins-in-completion',
    mixins: [FirstMixin, SecondMixin]
  }
</script>
""")
    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!,
                             "first-mixin-prop", "second-mixin-prop", "hi2dden",
                             "interesting-prop")
      assertDoesntContain(myFixture.lookupElementStrings!!,
                          "FirstMixinProp", "firstMixinProp",
                          "SecondMixinProp", "secondMixinProp", "Hi2dden",
                          "InterestingProp", "interestingProp")
    }
  }

  fun testNoNotImportedMixinsInCompletion() {
    myFixture.configureByText("index.js", globalMixinText())
    myFixture.configureByText("FirstMixin.vue", """
<script>
  export default {
    props: ['FirstMixinProp']
  }
</script>
""")
    myFixture.configureByText("SecondMixin.vue", """
<script>
  export default {
    props: ['SecondMixinProp']
  }
</script>
""")
    myFixture.configureByText("NoNotImportedMixinsInCompletion.vue", """
<template>
  <local-comp <caret>></local-comp>
</template>
<script>
  export default {
    name: "local-comp"
  }
</script>
""")
    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "hi2dden", "interesting-prop")
      assertDoesntContain(myFixture.lookupElementStrings!!,
                          "Hi2dden", "interestingProp", "InterestingProp",
                          "FirstMixinProp", "firstMixinProp", "first-mixin-prop",
                          "SecondMixinProp", "secondMixinProp", "second-mixin-prop")
    }
  }

  fun testNoCompletionInVueAttributes() {
    JSTestUtils.withNoLibraries(project) {
      myFixture.configureByText("NoCompletionInVueAttributes.vue", """
<template>
    <ul v-show="open<caret>">
    </ul>
</template>
""")
      myFixture.completeBasic()
      assertSameElements(myFixture.lookupElementStrings!!)
    }
  }

  fun testTypeScriptCompletionFromPredefined() {
    JSTestUtils.withNoLibraries(project) {
      myFixture.configureByText("TypeScriptCompletionFromPredefined.vue", """
<script lang="ts">
    open<caret>
</script>
""")
      myFixture.completeBasic()
      assertSameElements(myFixture.lookupElementStrings!!, "open", "opener")
    }
  }

  fun testCustomDirectivesInCompletion() {
    myFixture.copyDirectoryToProject("../common/customDirectives", ".")
    myFixture.configureFromTempProjectFile("CustomDirectives.vue")
    val attribute = myFixture.findElementByText("style", XmlAttribute::class.java)
    TestCase.assertNotNull(attribute)
    myFixture.editor.caretModel.moveToOffset(attribute.textOffset - 1)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "v-focus", "v-local-directive", "v-some-other-directive",
                           "v-imported-directive")
  }

  fun testCustomDirectivesLinkedFilesInCompletion() {
    myFixture.copyDirectoryToProject("../common/customDirectivesLinkedFiles", ".")
    myFixture.configureVueDependencies()
    myFixture.configureFromTempProjectFile("CustomDirectives.html")
    val attribute = myFixture.findElementByText("style", XmlAttribute::class.java)
    TestCase.assertNotNull(attribute)
    myFixture.editor.caretModel.moveToOffset(attribute.textOffset - 1)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "v-focus", "v-local-directive", "v-some-other-directive",
                           "v-imported-directive")
  }

  fun testPrettyLookup() {
    myFixture.configureByText("PrettyLookup.vue", """
<template>
{{ call<caret> }}
</template>
<script>
  export default {
    methods: {
      callMe(aaa, bbb) {
        return 5;
      }
    }
  }
</script>
""")
    noAutoComplete {
      myFixture.completeBasic()
      assertNotNull(myFixture.lookupElements)
      val item: LookupElement? = myFixture.lookupElements?.firstOrNull { "callMe" == it.lookupString }
      assertNotNull(item)
      val presentation = renderReal(item!!)
      assertEquals("number", presentation.typeText)
      assertEquals("(aaa, bbb)" + getLocationPresentation(null, "PrettyLookup.vue"), presentation.tailText)
    }
  }

  fun testCompleteVBind() {
    val script =
      """
<script>
  export default {
    name: 'childComp',
    props: {
      twoWords: {
      }
    },
    methods: {
      useMe(){}
    }
  }
</script>
"""
    myFixture.configureByText("CompleteVBind.vue", """
<template>
<child-comp :<caret>></child-comp>
</template>
$script""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, ":two-words", ":hidden")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, ":use-me", ":onclick", ":onchange")

    myFixture.configureByText("CompleteVBind.vue", """
<template>
<a v-bind:<caret>></a>
</template>
$script""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "hidden", "key", "is")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!,
                                       ":use-me", ":two-words", "use-me", "two-words", "onclick", "onchange")

    myFixture.configureByText("User.vue", """
<template>
<child :<caret>></child>
</template>
<script>
  import Child from './CompleteVBind';
  export default {
    components: { Child }
  }
</script>
""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, ":two-words", ":about")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, ":use-me", ":v-for", ":v-bind", ":onclick", ":onchange")
  }

  fun testVueOutObjectLiteral() {
    doLookupTest(VueTestModule.VUE_2_5_3, renderPriority = false, renderTypeText = false)
  }

  fun testVueOutObjectLiteralTs() {
    doLookupTest(VueTestModule.VUE_2_5_3, renderPriority = false, renderTypeText = false)
  }

  fun testVueOutObjectLiteralCompletionJsx() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("VueOutObjectLiteralCompletionJsx.vue", """
    <script lang="jsx">
      export default {
        before<caret>
      }
    </script>
""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "beforeCreate", "beforeDestroy", "beforeUpdate", "beforeMount")
  }

  fun testNoDoubleCompletionForLocalComponent() {
    myFixture.configureByText("AnotherPanel.vue", """
<script>
  import Comp from "./Comp";

  export default {
    components: {Comp},
    name: "another-panel",
    methods: {
      displayDetails() {

      }
    }
</script>""")
    myFixture.configureByText("NoDoubleCompletionForLocalComponent.vue", """
<template>
<<caret>
</template>

<script>
  import AnotherPanel from "./AnotherPanel";

  export default {
    name: 'comp',
    components: {
      AnotherPanel
    },
  }
</script>
""")
    myFixture.completeBasic()
    val cnt = myFixture.lookupElementStrings!!.filter { "another-panel" == it }.count()
    TestCase.assertEquals(1, cnt)
  }

  fun testElementUiCompletion() {
    myFixture.configureVueDependencies(VueTestModule.ELEMENT_UI_2_0_5)
    myFixture.configureByText("ElementUiCompletion.vue",
                              """
<template><el-<caret></template>
""")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, listOf("el-col", "el-button", "el-button-group"))
  }

  fun testMintUiCompletion() {
    myFixture.configureVueDependencies(VueTestModule.MINT_UI_2_2_3)
    myFixture.configureByText("MintUiCompletion.vue",
                              """
<template><mt-<caret></template>
""")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, listOf("mt-field", "mt-swipe", "mt-swipe-item"))
  }

  fun testVuetifyCompletion_017() {
    myFixture.configureVueDependencies(VueTestModule.VUETIFY_0_17_2)
    myFixture.configureByText("VuetifyCompletion.vue",
                              """
<template><<caret></template>
""")
    myFixture.completeBasic()
    val vuetifyComponents = VUETIFY_UNRESOLVED_COMPONENTS_WITH_PASCAL_CASE.toMutableList()
    vuetifyComponents.removeAll(listOf("v-breadcrumbs-divider", "VBreadcrumbsDivider",
                                       "v-autocomplete", "VAutocomplete",
                                       "v-scroll-x-transition", "VScrollXTransition",
                                       "v-scroll-y-transition", "VScrollYTransition",
                                       "v-scroll-x-reverse-transition", "VScrollXReverseTransition",
                                       "v-scroll-y-reverse-transition", "VScrollYReverseTransition",
                                       "v-tab-item", "VTabItem"))
    assertContainsElements(myFixture.lookupElementStrings!!, listOf("v-list", "v-list-group", "v-list-tile", "v-list-tile-action"))
    assertContainsElements(myFixture.lookupElementStrings!!, vuetifyComponents)
  }

  fun testVuetifyCompletion_137() {
    myFixture.configureVueDependencies(VueTestModule.VUETIFY_1_3_7)
    myFixture.configureByText("VuetifyCompletion.vue",
                              """
<template><<caret></template>
""")
    myFixture.completeBasic()
    val vuetifyComponents = VUETIFY_UNRESOLVED_COMPONENTS_WITH_PASCAL_CASE.toList()
    assertContainsElements(myFixture.lookupElementStrings!!, ContainerUtil.concat(vuetifyComponents, listOf("v-alert")))
  }

  fun testVuetifyCompletion_1210() {
    myFixture.configureVueDependencies(VueTestModule.VUETIFY_1_2_10)
    myFixture.configureByText("VuetifyCompletion.vue",
                              """
<template><<caret></template>
""")
    myFixture.completeBasic()
    val vuetifyComponents = VUETIFY_UNRESOLVED_COMPONENTS_WITH_PASCAL_CASE.toMutableList()
    vuetifyComponents.removeAll(listOf("v-breadcrumbs-divider", "VBreadcrumbsDivider"))
    assertContainsElements(myFixture.lookupElementStrings!!, ContainerUtil.concat(vuetifyComponents, listOf("v-btn")))
  }

  fun testIviewCompletion() {
    myFixture.configureVueDependencies(VueTestModule.IVIEW_2_8_0)
    myFixture.configureByText("IviewCompletion.vue",
                              """
<template><a<caret></template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, listOf("affix", "alert", "auto-complete", "avatar"))
  }

  fun testIview3Completion() {
    doLookupTest(VueTestModule.IVIEW_3_5_4, locations = listOf("v-bind:<caret>", "v-on:<caret>")) {
      !it.lookupString.startsWith("aria-")
    }
  }

  fun testBootstrapVueCompletion() {
    myFixture.configureVueDependencies(VueTestModule.BOOTSTRAP_VUE_2_0_0_RC_11)
    myFixture.configureByText("BoostrapVue.vue",
                              """
<template><<caret></template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!,
                                          listOf("b-form", "b-form-row", "b-form-text", "b-form-invalid-feedback"))
  }

  fun testShardsVueCompletion() {
    myFixture.configureVueDependencies(VueTestModule.SHARDS_VUE_1_0_5)
    myFixture.configureByText("ShardsVue.vue",
                              """
<template><<caret></template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, listOf("d-alert", "DAlert"))
  }

  fun testWrongPropsNotInCompletion() {
    myFixture.configureByText("WrongPropsNotInCompletion.vue", """
<template>
    <test-comp <caret>></test-comp>
</template>

<script>
    export default {
        name: "test-comp",
        props: ["aaa", `sss`, 'ddd', true, 123]
    };
</script>
""")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "aaa", "v-for", "ddd", "sss",
                           "class", "about", "onclick", "v-bind", "v-bind:", "v-on:")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, ":aaa", ":ddd", ":sss", ":about", ":onclick", "123", "true")

    myFixture.type(":")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, ":aaa", ":ddd", ":sss", ":about")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!,
                                       "aaa", "v-for", "ddd", "sss", "v-bind", ":v-bind", ":onclick")

    myFixture.type("a")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, ":aaa", ":about")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, "aaa", "v-for", "ddd", "sss", "v-bind",
                                       ":ddd", ":sss", ":onclick", "v-bind:", "v-on:")
  }

  fun testBuefyCompletion() {
    myFixture.configureVueDependencies(VueTestModule.BUEFY_0_6_2)
    myFixture.configureByText("BuefyCompletion.vue",
                              """
<template><b-<caret></template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertSameElements(myFixture.lookupElementStrings!!, listOf("b-autocomplete", "b-checkbox",
                                                                               "b-checkbox-button", "b-radio", "b-radio-button"))
  }

  fun testClassComponentCompletion() {
    createTwoClassComponents(myFixture)
    myFixture.configureByText("ClassComponentCompletion.vue",
                              """
<template>
  <<caret>
</template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!,
                                          listOf("ShortComponent", "LongVue", "short-component", "long-vue"))
  }

  fun testClassComponentCompletionTs() {
    createTwoClassComponents(myFixture, true)
    myFixture.configureByText("ClassComponentCompletionTs.vue",
                              """
<template>
  <<caret>
</template>
""")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!,
                                          listOf("ShortComponent", "LongVue", "short-component", "long-vue"))
  }

  fun testComponentInsertion() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10,
                                       additionalDependencies = mapOf(VUE_CLASS_COMPONENT to "*"))
    val data = listOf(
      Pair("""<template>
  <Sho<caret>
</template>
""", """<template>
  <ShortComponent
</template>
<script>
import Vue from "vue";
import Component from "vue-class-component";
import ShortComponent from "./ShortComponent.vue";

@Component({
  components: {ShortComponent}
})
export default class ComponentInsertion extends Vue {
}
</script>"""),
      Pair("""<template>
  <Sho<caret>
</template>
<script></script>
""", """<template>
  <ShortComponent
</template>
<script>
import Vue from "vue";
import Component from "vue-class-component";
import ShortComponent from "./ShortComponent.vue";

@Component({
  components: {ShortComponent}
})
export default class ComponentInsertion extends Vue {
}
</script>
"""),
      Pair("""<template>
  <Sho<caret>
</template>
<script>
import Vue from "vue";
</script>
""", """<template>
  <ShortComponent
</template>
<script>
import Vue from "vue";
import Component from "vue-class-component";
import ShortComponent from "./ShortComponent.vue";

@Component({
  components: {ShortComponent}
})
export default class ComponentInsertion extends Vue {
}

</script>
"""),
      Pair("""<template>
  <Sho<caret>
</template>
<script>
import Component from "vue-class-component";
</script>
""", """<template>
  <ShortComponent
</template>
<script>
import Component from "vue-class-component";
import Vue from "vue";
import ShortComponent from "./ShortComponent.vue";

@Component({
  components: {ShortComponent}
})
export default class ComponentInsertion extends Vue {
}

</script>
"""),
      Pair("""<template>
  <Sho<caret>
</template>
<script>
import Vue from "vue";
import Component from "vue-class-component";
@Component({
  name: "a123"
})
export default class ComponentInsertion extends Vue {
}
</script>
""", """<template>
  <ShortComponent
</template>
<script>
import Vue from "vue";
import Component from "vue-class-component";
import ShortComponent from "./ShortComponent.vue";
@Component({
  name: "a123",
  components: {ShortComponent}
})
export default class ComponentInsertion extends Vue {
}
</script>
""")
    )
    myFixture.configureByText("package.json", """{
          dependencies: {
            "vue-class-component" : "latest"
          }
        }""")
    createTwoClassComponents(myFixture, true)
    data.forEach {
      myFixture.configureByText("ComponentInsertion.vue", it.first)
      myFixture.completeBasic()
      myFixture.checkResult(it.second)
    }
  }

  fun testTypescriptVForItemCompletion() {
    myFixture.configureByText("TypescriptVForItemCompletion.vue", """
<template>
    <ul>
        <li v-for="item in goodTypes">{{item.<caret>}}</li>
    </ul>
</template>

<script lang="ts">
    import { Component, Prop, Vue } from 'vue-property-decorator';

    @Component
    export default class HelloWorld extends Vue {
        @Prop() private msg!: string;
        goodTypes: Array<string> = [];
    }
</script>
""")
    myFixture.completeBasic()

    checkJSStringCompletion(myFixture.lookupElements!!, false)
  }

  fun testTypescriptVForCompletionWebTypes() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("TypescriptVForCompletionWebTypes.vue",
                              "<template><div v-for=\"fooBar1 in goodTypes\">{{<caret>}}</li></template>")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "fooBar1")
  }

  fun testLocalComponentsExtendsCompletion() {
    createLocalComponentsExtendsData(myFixture, false)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "prop-from-a")
  }

  fun testCompletionWithRecursiveMixins() {
    defineRecursiveMixedMixins(myFixture)
    myFixture.configureByText("CompletionWithRecursiveMixins.vue", """
        <template>
          <<caret>
        </template>
      """)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, listOf("hidden-component", "HiddenComponent",
                                                                    "OneMoreComponent", "one-more-component"))
    myFixture.configureByText("CompletionWithRecursiveMixins2.vue", """
        <template>
          <HiddenComponent <caret>/>
        </template>
      """)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, listOf("from-d", "from-hidden"))
  }

  fun testNoImportInsertedForRecursivelyLocalComponent() {
    noAutoComplete {
      defineRecursiveMixedMixins(myFixture)
      myFixture.configureByText("CompletionWithRecursiveMixins.vue", """
        <template>
          <HiddenComponen<caret>
        </template>
      """)
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "HiddenComponent")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
        <template>
          <HiddenComponent from-hidden="" from-d=""<caret>
        </template>
      """)
    }

  }

  fun testCssClassInPug() {
    myFixture.configureByText("foo.vue", "<template lang='pug'>\n" +
                                         "    .<caret>\n" +
                                         "</template>\n" +
                                         "<style>\n" +
                                         "    .someClass {}\n" +
                                         "</style>")
    myFixture.completeBasic()
    myFixture.assertPreferredCompletionItems(0, "someClass")
  }

  fun testEventsAfterAt() {
    myFixture.configureVueDependencies(VueTestModule.BOOTSTRAP_VUE_2_0_0_RC_11)
    myFixture.configureByText("foo.vue", "<template> <BAlert @<caret> </template>")
    myFixture.completeBasic()
    myFixture.assertPreferredCompletionItems(0, // first 3 items come from the BAlert component
                                             "@dismiss-count-down", "@dismissCountDown", "@dismissed", "@input",
                                             "@abort", "@auxclick", "@beforeinput", "@beforematch", "@blur", "@cancel", "@canplay",
                                             "@canplaythrough", "@change", "@click", "@close", "@contextlost", "@contextmenu",
                                             "@contextrestored", "@copy", "@cuechange", "@cut", "@dblclick")

    myFixture.configureByText("foo.vue", "<template> <BAlert @inp<caret> </template>")
    myFixture.completeBasic()
    myFixture.assertPreferredCompletionItems(0, "@input", ".capture", ".once", ".passive", ".prevent")

    myFixture.configureByText("foo.vue", "<template> <div @c<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "@copy", "@cancel", "@click", "@canplaythrough", "@close",
                       "@change", "@canplay", "@cut", "@cuechange", "@contextlost", "@contextmenu", "@contextrestored", ".capture",
                       ".once", ".passive", ".prevent", ".self", ".stop")
  }

  fun testEventsAfterVOn() {
    myFixture.configureByText("foo.vue", "<template> <MyComponent v-on:cl<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "auxclick", "click", "close", "dblclick", ".capture", ".once", ".passive",
                       ".prevent", ".self", ".stop")

    myFixture.configureByText("foo.vue", "<template> <div v-on:<caret> </template>")
    myFixture.completeBasic()
    myFixture.assertPreferredCompletionItems(0, "abort", "auxclick", "beforeinput", "beforematch", "blur",
                                             "cancel", "canplay", "canplaythrough", "change", "click")
  }


  fun testEventModifiers() {
    // general modifiers only
    myFixture.configureByText("foo.vue", "<template> <MyComponent @click123.<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, ".stop", ".prevent", ".capture", ".self", ".once", ".passive")

    // general modifiers (except already used) + key modifiers + system modifiers
    myFixture.configureByText("foo.vue", "<template> <div v-on:keyup.stop.passive.<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, ".prevent", ".capture", ".self", ".once",
                       ".enter", ".tab", ".delete", ".esc", ".space", ".up", ".down", ".left", ".right",
                       ".ctrl", ".alt", ".shift", ".meta", ".exact")

    // general modifiers (except already used) + mouse button modifiers + system modifiers
    myFixture.configureByText("foo.vue", "<template> <div @click.capture.<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, ".stop", ".prevent", ".self", ".once", ".passive",
                       ".left", ".right", ".middle",
                       ".ctrl", ".alt", ".shift", ".meta", ".exact")

    // general modifiers + system modifiers
    myFixture.configureByText("foo.vue", "<template> <div @drop.<caret> </template>")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, ".stop", ".prevent", ".capture", ".self", ".once", ".passive",
                       ".ctrl", ".alt", ".shift", ".meta", ".exact")
  }

  fun testAutopopupAfterVOnSelection() {
    myFixture.configureByText("a.vue", "<div v-o<caret>>")
    myFixture.completeBasic()
    myFixture.assertPreferredCompletionItems(0, "v-on:", "v-on")
    (myFixture.lookup as LookupImpl).finishLookup(Lookup.NORMAL_SELECT_CHAR)
    // new completion must start
    myFixture.assertPreferredCompletionItems(0, "abort", "auxclick", "beforeinput", "beforematch", "blur", "cancel", "canplay")
    (myFixture.lookup as LookupImpl).finishLookup(Lookup.NORMAL_SELECT_CHAR)
    myFixture.checkResult("<div v-on:abort=\"<caret>\">")
  }

  fun testStyleAttributes() {
    myFixture.configureByText("foo.vue", "<style <caret>></style>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "scoped", "src", "module")
    assertDoesntContain(myFixture.lookupElementStrings!!, "functional")
  }

  fun testTemplateAttributes() {
    myFixture.configureByText("foo.vue", "<template <caret>></template>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "functional", "v-if", "v-else")
    assertDoesntContain(myFixture.lookupElementStrings!!, "scoped", "module")
  }

  fun testNoVueTagsWithNamespace() {
    myFixture.configureByText("foo.vue", """
      <template>
        <foo:tran<caret>/>
      </template>""")
    myFixture.completeBasic()
    assertNullOrEmpty(myFixture.lookupElementStrings)
  }

  fun testVueCompletionInsideScript() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("test.vue", "<script>\n" +
                                          "    export default {\n" +
                                          "        name: 'test',\n" +
                                          "        data() {\n" +
                                          "            return {testItem: 10}\n" +
                                          "        },\n" +
                                          "        props : {\n" +
                                          "          props1: true\n" +
                                          "        },\n" +
                                          "        methods: {\n" +
                                          "            method1() {}\n" +
                                          "        },\n" +
                                          "        computed: {\n" +
                                          "            dataData() {this.<caret> }\n" +
                                          "        }\n" +
                                          "    }\n" +
                                          "</script>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "testItem", "props1", "method1")
  }

  fun testVueCompletionInsideScriptLifecycleHooks() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("test.vue", "<script>\n" +
                                          "    export default {\n" +
                                          "        computed: {\n" +
                                          "            dataData() {this.<caret> }\n" +
                                          "        }\n" +
                                          "    }\n" +
                                          "</script>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "\$el", "\$options", "\$parent", "\$props")
  }

  fun testVueCompletionInsideScriptNoLifecycleHooksTopLevel() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("test.vue", "<script>\n" +
                                          "    export default {\n" +
                                          "        this.<caret> " +
                                          "    }\n" +
                                          "</script>")
    assertDoesntContainVueLifecycleHooks()
  }

  fun testVueCompletionInsideScriptNoLifecycleHooksWithoutThis() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("test.vue", "<script>\n" +
                                          "    export default {\n" +
                                          "        methods: {name(){<caret>}} " +
                                          "    }\n" +
                                          "</script>")

    assertDoesntContainVueLifecycleHooks()
  }

  fun testVueCompletionWithExtend() {
    myFixture.configureByText("a-component.vue", """<script>export default Vue.extend({props:{msg: String}})</script>""")
    myFixture.configureByText("b-component.vue", """
      <template>
        <HW <caret>/>
      </template>
      <script>
        import Vue from "vue"
        import HW from './a-component.vue'
        
        export default Vue.extend({
            name: 'app',
            components: {
                HW
            },
        });
      </script>
    """)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "msg")
  }

  fun testVueNoComponentNameAsStyleSelector() {
    myFixture.configureByText("A.vue", """<template><div/></template>""")
    myFixture.configureByText("B.vue", """
      <template>
        <div/>
      </template>
      <style lang="scss">
      <caret>
      </style>
    """)
    myFixture.completeBasic()
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, "A")
  }

  fun testCompletionPriorityAndHints() {
    doLookupTest(VueTestModule.VUETIFY_1_2_10, VueTestModule.SHARDS_VUE_1_0_5, dir = true, lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testCompletionPriorityAndHintsBuiltInTags() {
    doLookupTest(VueTestModule.VUE_2_5_3, lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testDirectiveCompletionOnComponent() {
    doLookupTest(VueTestModule.VUETIFY_1_3_7, renderPriority = false, renderTypeText = false, containsCheck = true)
  }

  fun testBuiltInTagsAttributeCompletion() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByText("a-component.vue", """
      <template>
        <transition <caret>>
      </template>
    """)
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "appear-active-class", "css", "leave-class")
  }

  fun testBindProposalsPriority() {
    doLookupTest(VueTestModule.VUETIFY_1_2_10, VueTestModule.VUE_2_6_10, renderTypeText = false, renderProximity = true) {
      !it.lookupString.contains("aria-") && !it.lookupString.startsWith("on")
    }
  }

  fun testBindProposalsStdTag() {
    doLookupTest(VueTestModule.VUE_2_6_10, renderPriority = false, renderTypeText = false) {
      !it.lookupString.contains("aria-")
    }
  }

  private fun doAttributeNamePriorityTest(vueVersion: VueTestModule) {
    doLookupTest(VueTestModule.VUETIFY_1_2_10, vueVersion,
                 fileContents = """
         <template>
          <v-alert <caret>
        <template>
      """.trimIndent(),
                 renderTypeText = false,
                 renderProximity = true) {
      !it.lookupString.contains("aria-") && !it.lookupString.startsWith("on")
    }
  }

  fun testAttributeNamePriorityVue26() {
    doAttributeNamePriorityTest(VueTestModule.VUE_2_6_10)
  }

  fun testAttributeNamePriorityVue30() {
    doAttributeNamePriorityTest(VueTestModule.VUE_3_0_0)
  }

  fun testAttributeNamePriorityVue31() {
    doAttributeNamePriorityTest(VueTestModule.VUE_3_1_0)
  }

  fun testAttributeNamePriorityVue32() {
    doAttributeNamePriorityTest(VueTestModule.VUE_3_2_2)
  }

  fun testComplexComponentDecoratorCompletion() {
    myFixture.copyDirectoryToProject("complexComponentDecorator", ".")
    myFixture.configureFromTempProjectFile("App.vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!,
                           "component-prop", "mixin-prop", "decorated-mixin-prop", "decorated-mixin-prop2")
    assertDoesntContain(myFixture.lookupElementStrings!!, "decorated-mixin-prop3")
  }

  fun testComplexComponentDecoratorCompletionTs() {
    myFixture.copyDirectoryToProject("complexComponentDecoratorTs", ".")
    myFixture.configureFromTempProjectFile("App.vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!,
                           "component-prop", "mixin-prop", "decorated-mixin-prop", "decorated-mixin-prop2")
    assertDoesntContain(myFixture.lookupElementStrings!!, "decorated-mixin-prop3")
  }

  fun testComplexComponentDecoratorCompletion8Ts() {
    myFixture.copyDirectoryToProject("complexComponentDecorator8Ts", ".")
    myFixture.configureFromTempProjectFile("App.vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!,
                           "component-prop", "mixin-prop", "decorated-mixin-prop", "decorated-mixin-prop2")
    assertDoesntContain(myFixture.lookupElementStrings!!, "decorated-mixin-prop3")
  }

  fun testComponentDecoratorCompletion8Name() {
    myFixture.copyDirectoryToProject("complexComponentDecorator8Ts", ".")
    myFixture.configureFromTempProjectFile("App.vue")
    myFixture.moveToOffsetBySignature("<<caret>HW")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!,
                           "Empty", "empty", "HelloWorld", "hello-world")
    assertDoesntContain(myFixture.lookupElementStrings!!, "EmptyClass")
  }

  fun testDestructuringVariableTypeInVFor() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_5_3)
    myFixture.configureByFile(getTestName(true) + ".vue")
    myFixture.completeBasic()
    assertStartsWith(myFixture.lookupElements!!, "first", "last")
  }

  fun testWebTypesComplexSetup() {
    myFixture.copyDirectoryToProject("web-types", ".")
    listOf(Triple("root.vue",
                  listOf("root", "sub1", "root-sibling", "root-pkg", "foo1-pkg", "foo1Pkg"),
                  listOf("foo2-pkg", "foo2Pkg", "Foo1Pkg")),

           Triple("sub1/sub1.vue",
                  listOf("sub1", "su1a", "foo1-pkg", "foo2-pkg", "foo2Pkg"),
                  listOf("root", "root-pkg", "root-sibling", "sub2")),

           Triple("sub2/sub2.vue",
                  listOf("sub2", "sub2-pkg", "foo1-pkg", "bar1-pkg"),
                  listOf("root", "root-pkg", "root-sibling", "sub1", "foo2-pkg")))
      .forEach { (fileName, expected, notExpected) ->
        myFixture.configureFromTempProjectFile(fileName)
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings!!, expected)
        assertDoesntContain(myFixture.lookupElementStrings!!, notExpected)
      }
  }

  fun testSlotProps() {
    doLookupTest(renderPriority = true, renderTypeText = false) {
      // Ignore global objects and keywords
      it.priority > 10
    }
  }

  fun testVueDefaultSymbolsWithDefinitions() {
    doLookupTest(VueTestModule.VUE_2_5_3, locations = listOf(
      "v-on:click=\"<caret>\"",
      "v-bind:about=\"<caret>\""
    )) {
      // Ignore global objects and keywords
      it.priority > 10
    }
  }

  fun testVueDefaultSymbols() {
    doLookupTest(locations = listOf(
      "v-on:click=\"<caret>\"",
      "v-bind:about=\"<caret>\""
    )) { info ->
      info.priority >= JSLookupPriority.NON_CONTEXT_KEYWORDS_PRIORITY.priorityValue
      || info.lookupString.startsWith("A") // Check also if we have some global symbols, but don't list all of them
    }
  }

  fun testSlotTag() {
    doLookupTest(VueTestModule.VUE_2_5_3, containsCheck = true, renderPriority = true, renderTypeText = false)
  }

  fun testSlotNameCompletion() {
    myFixture.configureVueDependencies("some_lib" to "0.0.0")
    myFixture.copyDirectoryToProject("slotNames", ".")
    myFixture.configureByFile("test.vue")

    for ((tag, slots) in listOf(
      Pair("script-template-vue", listOf("scriptTemplateVue1", "scriptTemplateVue2", "default")),
      Pair("require-decorators", listOf("requireDecorators1", "requireDecorators2", "default")),
      Pair("x-template", listOf("xTemplate1", "xTemplate2", "default")),
      Pair("export-import", listOf("exportImport1", "exportImport2")),
      Pair("some-lib", listOf("someLib1", "someLib2")),
      Pair("no-script-section", listOf("noScriptSection1", "noScriptSection2", "default"))
    )) {
      for (signature in listOf("$tag><template v-slot:<caret></$tag", "$tag><div slot=\"<caret>\"</$tag")) {
        myFixture.moveToOffsetBySignature(signature)
        myFixture.completeBasic()
        assertEquals(signature, slots.sorted(), myFixture.lookupElementStrings!!.sorted())
      }
    }
  }

  fun testFilters() {
    myFixture.configureVueDependencies("some_lib" to "0.0.0")
    doLookupTest(dir = true, renderTypeText = false, renderPriority = false)
  }

  fun testComplexThisContext() {
    myFixture.addFileToProject("index.ts", "import \"vuex\"")
    doLookupTest(VueTestModule.VUEX_3_1_0, VueTestModule.VUE_2_5_3,
                 renderTypeText = false, renderPriority = false)
  }

  fun testTopLevelTagsNoI18n() {
    myFixture.configureVueDependencies()

    myFixture.configureByText("test.vue", "<<caret>")
    myFixture.completeBasic()
    assertDoesntContain(myFixture.lookupElementStrings!!, "i18n")
  }

  fun testTopLevelTags() {
    myFixture.configureVueDependencies("vue-i18n" to "*")
    val toTest = listOf(
      Pair("i18n", listOf("lang")),
      Pair("template", listOf("lang", "src", "functional")),
      Pair("script", listOf("lang", "id", "src")),
      Pair("style", listOf("lang", "src", "module", "scoped"))
    )
    myFixture.configureByText("test.vue", "<<caret>")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, toTest.map { it.first })

    for ((tag, list) in toTest) {
      myFixture.configureByText("test.vue", "<$tag <caret>")
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, list)
    }
  }

  fun testScriptLangAttributeWithAlreadyPresentCode() {
    myFixture.configureByText("Test.vue", """
      <script la<caret>>
      export default {
        name: "Test"
      };
      </script>
    """.trimIndent())
    myFixture.completeBasic()
    myFixture.finishLookup('\t')
    myFixture.checkResult("""
      <script lang="<caret>">
      export default {
        name: "Test"
      };
      </script>
    """.trimIndent())
  }

  fun testComputedTypeTS() {
    doLookupTest(VueTestModule.VUE_2_6_10, renderPriority = false, locations = listOf(
      "{{ a<caret>",
      "this.<caret>"
    ))
  }

  fun testComputedTypeJS() {
    doLookupTest(VueTestModule.VUE_2_6_10, renderPriority = false, locations = listOf(
      "{{ a<caret>",
      "this.<caret>"
    ))
  }

  fun testDataTypeTS() {
    doLookupTest(VueTestModule.VUE_2_6_10, renderPriority = false, locations = listOf(
      "this.<caret>msg\"",
      "= this.<caret>userInput"
    )) {
      !it.lookupString.startsWith("\$")
    }
  }

  fun testCustomModifiers() {
    myFixture.configureVueDependencies("test-lib" to "0.0.0")
    doLookupTest(dir = true, renderPriority = false, renderTypeText = false)
  }

  fun testVue2CompositionApi() {
    doLookupTest(VueTestModule.VUE_2_6_10, VueTestModule.COMPOSITION_API_0_4_0, locations = listOf(
      "{{<caret>}}",
      "{{foo.<caret>}}",
      "{{state.<caret>count}}"
    )) {
      // Ignore global objects and keywords
      it.priority > 10
    }
  }

  fun testVue3CompositionApi() {
    // Used TS type is recursive in itself and recursion prevention is expected
    RecursionManager.disableAssertOnRecursionPrevention(myFixture.testRootDisposable)
    RecursionManager.disableMissedCacheAssertions(myFixture.testRootDisposable)

    doLookupTest(VueTestModule.VUE_3_0_0, locations = listOf(
      "{{<caret>}}",
      "{{foo.<caret>}}",
      "{{state.<caret>count}}"
    )) {
      // Ignore global objects and keywords
      it.priority > 10
    }
  }

  fun testDefineComponent() {
    doLookupTest(VueTestModule.VUE_2_5_3, VueTestModule.COMPOSITION_API_0_4_0,
                 dir = true, fileContents = """<template><<caret></template>""", lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testDefineOptions() {
    doLookupTest(VueTestModule.VUE_3_3_4, dir = true,
                 fileContents = """<template><<caret></template>""", lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testNoDuplicateCompletionProposals() {
    myFixture.configureByFile("noDupedAttrs.vue")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "v-dir2", "v-on:", "v-bind:")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, "v-dir1", "v-slot", "v-slot:")
    myFixture.type("v-on:")
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, "click", "dblclick")
    myFixture.type("\n\" :")
    myFixture.completeBasic()
    UsefulTestCase.assertContainsElements(myFixture.lookupElementStrings!!, ":dir", ":bar")
    UsefulTestCase.assertDoesntContain(myFixture.lookupElementStrings!!, ":foo", ":id")
  }

  fun testPropsDataOptionsJS() {
    myFixture.configureByFile("propsDataOptionsJS.vue")
    for ((tests, results) in listOf(
      Pair(listOf("{{this.\$props.<caret>", "{{\$props.<caret>", "this.\$props.<caret>foo"),
           listOf("mixinProp (typeText=null; priority=101.0; bold)", "aProp (typeText=null; priority=101.0; bold)")),
      Pair(listOf("{{this.\$data.<caret>", "{{\$data.<caret>", "this.\$data.<caret>foo"),
           listOf("mixinData (typeText='string'; priority=101.0; bold)", "foo (typeText='number'; priority=101.0; bold)",
                  "\$foo (typeText='number'; priority=101.0; bold)")),
      Pair(listOf("{{this.\$options.<caret>", "{{\$options.<caret>", "this.\$options.<caret>foo"),
           listOf("customOption (typeText='string'; priority=101.0; bold)", "customStuff (typeText='number'; priority=101.0; bold)",
                  "props (typeText=null; priority=101.0; bold)", "name (typeText='string'; priority=101.0; bold)"))
    )) {
      for (test in tests) {
        try {
          myFixture.moveToOffsetBySignature(test)
          myFixture.completeBasic()
          UsefulTestCase.assertContainsElements(myFixture.renderLookupItems(true, true), results)
          PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        catch (e: ComparisonFailure) {
          throw ComparisonFailure(test + ":" + e.message, e.expected, e.actual).initCause(e)
        }
        catch (e: AssertionError) {
          throw AssertionError(test + ":" + e.message, e)
        }
      }
    }
    myFixture.moveToOffsetBySignature("this.<caret>\$options.foo")
    myFixture.completeBasic()
    myFixture.renderLookupItems(false, true).let {
      assertContainsElements(it, "foo (typeText='number')")
      assertDoesntContain(it, "\$foo (typeText='number')")
    }
  }

  fun testSassGlobalFunctions() {
    WorkspaceEntityLifecycleSupporterUtils.withAllEntitiesInWorkspaceFromProvidersDefinedOnEdt(myFixture.project) {
      doLookupTest(renderTypeText = false)
    }
  }

  fun testImportEmptyObjectInitializerComponent() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10)
    myFixture.configureByText("FooBar.vue", "<script>export default {}</script>")
    myFixture.configureByText("check.vue", "<template><<caret></template>")
    myFixture.completeBasic()
    myFixture.type("foo-\n")
    myFixture.checkResult("""<template><foo-bar</template>
<script>
import FooBar from "./FooBar.vue";

export default {
  components: {FooBar}
}
</script>""")
  }

  fun testImportFunctionPropertyObjectInitializerComponent() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10)
    myFixture.configureByText("FooBar.vue", "<script>export default {data(){}}</script>")
    myFixture.configureByText("check.vue", "<template><<caret></template>")
    myFixture.completeBasic()
    myFixture.type("foo-\n")
    myFixture.checkResult("""<template><foo-bar</template>
<script>
import FooBar from "./FooBar.vue";

export default {
  components: {FooBar}
}
</script>""")
  }

  fun testCastedObjectProps() {
    doLookupTest(VueTestModule.VUE_3_2_2, locations = listOf(
      "post.<caret>",
      "callback.<caret>",
      "message.<caret>"
    ))
  }

  fun testImportVueExtend() {
    doLookupTest(dir = true)
  }

  fun testScriptSetup() {
    doLookupTest(
      VueTestModule.VUE_3_2_2,
      renderPriority = false,
      locations = listOf(
        ":count=\"<caret>count\"",
        " v<caret>/>",
        ":<caret>foo=",
        "<<caret>Foo",
      ),
      lookupFilter = filterOutStandardHtmlSymbols
        and filterOutMostOfGlobalJSSymbolsInVue
        and { info -> info.lookupString.let { !it.startsWith("aria-") && !it.startsWith("on") && !it.startsWith(":aria-") } }
    )
  }

  fun testScriptSetupTs() {
    TypeScriptTestUtil.setStrictNullChecks(project, testRootDisposable)
    doLookupTest(
      VueTestModule.VUE_3_2_2,
      renderPriority = false,
      locations = listOf(
        ":count=\"<caret>count\"",
        " v<caret>/>",
        ":<caret>foo=",
        "<<caret>Foo",
      ),
      lookupFilter = filterOutStandardHtmlSymbols
        and filterOutMostOfGlobalJSSymbolsInVue
        and { info -> info.lookupString.let { !it.startsWith("aria-") && !it.startsWith("on") && !it.startsWith(":aria-") } }
    )
  }

  fun testExpression() {
    doLookupTest(VueTestModule.VUE_2_6_10) {
      // Ignore global objects
      it.priority > 1
    }
  }

  fun testObjectLiteralProperty() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testEnum() {
    doLookupTest(VueTestModule.VUE_3_2_2) {
      // Ignore global objects and keywords
      it.priority > 10
    }
  }

  fun testScriptSetupRef() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testScriptSetupGlobals() {
    doLookupTest(VueTestModule.VUE_3_2_2)
    myFixture.type("Pro")
    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    myFixture.checkResult("""
      <script setup>
      defineProps()
      </script>
    """.trimIndent())
  }

  fun testScriptSetupGlobalsTs() {
    doLookupTest(VueTestModule.VUE_3_2_2, dir = true)
    myFixture.type("Pro")
    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    myFixture.checkResult("""
      <script setup lang="ts">
      defineProps()
      </script>
    """.trimIndent())
  }

  fun testTypedComponentsImportClassic() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_0_0, VueTestModule.HEADLESS_UI_1_4_1)
    myFixture.configureByText("text.vue", "<template><Dial<caret></template>\n<script></script>")
    myFixture.completeBasic()
    myFixture.type('\n')
    myFixture.checkResultByFile("typedComponentsImportClassic.vue")
  }

  fun testTypedComponentsImportScriptSetup() {
    myFixture.configureVueDependencies(VueTestModule.HEADLESS_UI_1_4_1)
    myFixture.configureByText("text.vue", "<template><Dial<caret></template>")
    myFixture.completeBasic()
    myFixture.type('\n')
    myFixture.checkResultByFile("typedComponentsImportScriptSetup.vue")
  }

  fun testTypedComponentsImportScriptSetup2() {
    myFixture.configureVueDependencies(VueTestModule.NAIVE_UI_2_19_11_NO_WEB_TYPES)
    myFixture.configureByText("text.vue", "<template><n-a<caret></template>\n<script setup>\n</script>")
    myFixture.completeBasic()
    myFixture.type('\n')
    myFixture.checkResultByFile("typedComponentsImportScriptSetup2.vue")
  }

  fun testTypedComponentsPropsAndEvents() {
    TypeScriptTestUtil.setStrictNullChecks(project, testRootDisposable)
    doLookupTest(VueTestModule.VUE_3_2_2,
                 VueTestModule.HEADLESS_UI_1_4_1,
                 VueTestModule.ELEMENT_PLUS_2_1_11_NO_WEB_TYPES,
                 locations = listOf("Dialog v-bind:<caret>", "Dialog v-on:<caret>",
                                    "el-affix v-bind:<caret>", "el-affix v-on:<caret>")) {
      !it.lookupString.startsWith("aria-")
    }
  }

  fun testTypedComponentsList() {
    doLookupTest(VueTestModule.VUE_3_2_2,
                 VueTestModule.HEADLESS_UI_1_4_1,
                 VueTestModule.NAIVE_UI_2_19_11_NO_WEB_TYPES,
                 VueTestModule.ELEMENT_PLUS_2_1_11_NO_WEB_TYPES,
                 fileContents = """<template><<caret></template>""",
                 lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testStyleVBind() {
    doLookupTest(VueTestModule.VUE_3_2_2, renderPriority = false, renderTypeText = false)
  }

  fun testStyleVBindScriptSetupCss() {
    doLookupTest(VueTestModule.VUE_3_2_2, renderPriority = false, renderTypeText = true)
  }

  fun testStyleVBindScriptSetupScss() {
    WorkspaceEntityLifecycleSupporterUtils.withAllEntitiesInWorkspaceFromProvidersDefinedOnEdt(project) {
      doLookupTest(VueTestModule.VUE_3_2_2, renderPriority = false, renderTypeText = true)
    }
  }

  fun testStyleVBindScriptSetupSass() {
    WorkspaceEntityLifecycleSupporterUtils.withAllEntitiesInWorkspaceFromProvidersDefinedOnEdt(project) {
      doLookupTest(VueTestModule.VUE_3_2_2, renderPriority = false, renderTypeText = true)
    }
  }

  fun testStyleVBindScriptSetupLess() {
    doLookupTest(VueTestModule.VUE_3_2_2, renderPriority = false, renderTypeText = true)
  }

  fun testCreateAppIndex() {
    myFixture.copyDirectoryToProject("../common/createApp", ".")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.configureFromTempProjectFile("index.html")
    doLookupTest(renderPriority = true, noConfigure = true, locations = listOf("<<caret>Boo", "<div v-<caret>"),
                 lookupFilter = filterOutStandardHtmlSymbols)
    myFixture.moveToOffsetBySignature("w<<caret>")
    myFixture.completeBasic()
    assertDoesntContain(myFixture.lookupElementStrings!!, "Car", "Bar", "foo-bar")
  }

  fun testCreateAppIncludedComponent() {
    myFixture.copyDirectoryToProject("../common/createApp", ".")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.configureFromTempProjectFile("foo.vue")
    doLookupTest(renderPriority = true, noConfigure = true, locations = listOf("<<caret>Boo", "<div v-<caret>"),
                 lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testCreateAppRootComponent() {
    myFixture.copyDirectoryToProject("../common/createApp", ".")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.configureFromTempProjectFile("App.vue")
    doLookupTest(renderPriority = true, noConfigure = true, locations = listOf("<<caret>Boo", "<div v-<caret>"),
                 lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testCreateAppImportedByRootComponent() {
    myFixture.copyDirectoryToProject("../common/createApp", ".")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.configureFromTempProjectFile("ImportedByRoot.vue")
    myFixture.moveToOffsetBySignature("<<caret>div")
    doLookupTest(renderPriority = true, noConfigure = true, lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testCreateAppNotImported() {
    myFixture.copyDirectoryToProject("../common/createApp", ".")
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.configureFromTempProjectFile("NotImported.vue")
    myFixture.moveToOffsetBySignature("<<caret>div")
    doLookupTest(renderPriority = true, noConfigure = true, lookupFilter = filterOutStandardHtmlSymbols)
  }

  fun testSlotsWithPatterns() {
    doLookupTest(dir = true, renderPriority = false, renderDisplayText = true, locations = listOf("<template #<caret>"))
  }

  fun testSlotTypes() {
    doLookupTest(VueTestModule.VUE_3_2_2, VueTestModule.QUASAR_2_6_5,
                 dir = true, renderPriority = false,
                 locations = listOf("props.<caret>key", "{<caret>selected,"))
  }

  fun testAutoImportInsertion() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2, VueTestModule.HEADLESS_UI_1_4_1)
    myFixture.configureByFile(getTestName(true) + ".vue")
    myFixture.completeBasic()
    myFixture.type("al\n")
    myFixture.checkResultByFile(getTestName(true) + ".after.vue")
  }

  fun testScriptKeywordsJS() {
    doLookupTest(VueTestModule.VUE_3_2_2) {
      it.priority >= JSLookupPriority.NON_CONTEXT_KEYWORDS_PRIORITY.priorityValue
    }
  }

  fun testScriptKeywordsTS() {
    doLookupTest(VueTestModule.VUE_3_2_2) {
      it.priority >= JSLookupPriority.NON_CONTEXT_KEYWORDS_PRIORITY.priorityValue
    }
  }

  fun testExpressionOperationKeywordsJS() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testExpressionOperationKeywordsTS() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testExpressionOperationKeywordsNestedJS() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testExpressionOperationKeywordsNestedTS() {
    doLookupTest(VueTestModule.VUE_3_2_2)
  }

  fun testComponentEmitsDefinitions() {
    doLookupTest(VueTestModule.VUE_3_2_2, dir = true, renderPriority = true, renderTypeText = false,
                 locations = listOf("define-emits @<caret>", "define-component @<caret>", "export-component @<caret>",
                                    "define-emits-with-type @<caret>")) {
      it.isItemTextBold
    }
  }

  fun testExternalSymbolsImport() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.copyDirectoryToProject(getTestName(true), ".")

    fun doTest() {
      myFixture.moveToOffsetBySignature(":style=\"<caret>\"")
      myFixture.completeBasic()
      myFixture.typeWithWaitCoroutinesBlocking("Col\n.r")
      myFixture.completeBasic()
      myFixture.typeWithWaitCoroutinesBlocking("\n")

      myFixture.moveToOffsetBySignature(" {{ <caret> }}")
      myFixture.completeBasic()
      myFixture.typeWithWaitCoroutinesBlocking("getTe\n")

      myFixture.moveToOffsetBySignature("key) in i<caret>")
      myFixture.completeBasic()
      myFixture.typeWithWaitCoroutinesBlocking("tems\n")
    }

    myFixture.configureFromTempProjectFile("HelloWorld.vue")
    doTest()
    myFixture.checkResultByFile(getTestName(true) + "/HelloWorld.after.vue")

    myFixture.configureFromTempProjectFile("HelloWorldClassic.vue")
    doTest()
    myFixture.checkResultByFile(getTestName(true) + "/HelloWorldClassic.after.vue")
  }

  fun testImportComponentFromTextContext() {
    myFixture.copyDirectoryToProject(getTestName(true), ".")
    myFixture.configureFromTempProjectFile("Check.vue")
    myFixture.completeBasic()
    myFixture.checkResultByFile("${getTestName(true)}/Check.after.vue")
  }

  fun testImportNoScriptOrScriptSetupComponentInCode() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.copyDirectoryToProject(getTestName(true), ".")
    myFixture.configureByFile("test.ts")

    myFixture.moveToOffsetBySignature("foo: No<caret>")
    myFixture.completeBasic()
    myFixture.type("ScriptC\n")

    myFixture.moveToOffsetBySignature("bar: Sc<caret>")
    myFixture.completeBasic()
    myFixture.type("riptSet\n")

    myFixture.checkResultByFile("${getTestName(true)}/test.after.ts")

    myFixture.configureByFile("imports.ts")

    myFixture.moveToOffsetBySignature("import No<caret>")
    myFixture.completeBasic()
    myFixture.type("ScriptC\n")

    myFixture.moveToOffsetBySignature("import Sc<caret>")
    myFixture.completeBasic()
    myFixture.type("riptSet\n")

    myFixture.checkResultByFile("${getTestName(true)}/imports.after.ts")

  }

  fun testExternalScriptComponentEdit() {
    myFixture.copyDirectoryToProject(getTestName(true), ".")

    myFixture.configureFromTempProjectFile("foo.vue")

    myFixture.completeBasic()
    myFixture.type("Col\n.r")
    myFixture.completeBasic()
    myFixture.type("\n")

    WriteAction.run<Throwable> {
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      FileDocumentManager.getInstance().saveAllDocuments()
    }

    myFixture.checkResultByFile("${getTestName(true)}/foo.after.vue")

    myFixture.configureFromTempProjectFile("foo.js")
    myFixture.checkResultByFile("${getTestName(true)}/foo.after.js")
  }

  fun testExternalScriptComponentImport() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.copyDirectoryToProject(getTestName(true), ".")
    myFixture.configureFromTempProjectFile("test.vue")

    myFixture.type("<")
    myFixture.completeBasic()
    myFixture.type("fo\n :")
    myFixture.completeBasic()
    myFixture.type("ms\n")

    myFixture.checkResultByFile("${getTestName(true)}/test.after.vue")
  }

  fun testAliasedComponentImport() {
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("apps/vue-app/src/App.vue")

    myFixture.type("Co")
    myFixture.completeBasic()

    myFixture.checkResultByFile("${getTestName(true)}/apps/vue-app/src/App.after.vue")
  }

  fun testAliasedComponentImportKebabCase() {
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("apps/vue-app/src/App.vue")

    myFixture.type("Co")
    myFixture.completeBasic()

    myFixture.checkResultByFile("${getTestName(true)}/apps/vue-app/src/App.after.vue")
  }

  fun testAliasedComponentImportOptionsApi() {
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("apps/vue-app/src/App.vue")

    myFixture.type("Co")
    myFixture.completeBasic()

    myFixture.checkResultByFile("${getTestName(true)}/apps/vue-app/src/App.after.vue")
  }

  fun testImportComponentUsedInApp() {
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("src/components/CheckImportComponent.vue")

    myFixture.type("orl")
    myFixture.completeBasic()

    myFixture.checkResultByFile("${getTestName(true)}/src/components/CheckImportComponent.after.vue")
  }

  fun testVueTscComponent() {
    doLookupTest(VueTestModule.VUE_3_2_2, dir = true, renderPriority = true) {
      it.isItemTextBold
    }
  }

  fun testVueTscComponentQualifiedComponentType() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4, additionalDependencies = mapOf("vee-validate" to "1.0.0"))

    val name = getTestName(true)
    myFixture.copyDirectoryToProject(name, "")
    forceReloadProjectRoots(project)
    myFixture.configureFromTempProjectFile("$name.vue")

    myFixture.completeBasic()
    myFixture.type('\n')

    myFixture.checkResultByFile("$name.after.vue")
  }

  fun testVueTscComponentWithSlots() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_4_0, additionalDependencies = mapOf("vite-vue-testlib" to "0.0.2"))

    val name = getTestName(true)
    myFixture.copyDirectoryToProject(name, "")
    forceReloadProjectRoots(project)
    myFixture.configureFromTempProjectFile("$name.vue")

    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "prop1", "prop2")
  }

  fun testVueTscComponentAliasedExport() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_4_0, additionalDependencies = mapOf("@inertiajs/vue3" to "1.0.14"))

    val name = getTestName(true)
    myFixture.copyDirectoryToProject(name, "")
    forceReloadProjectRoots(project)
    myFixture.configureFromTempProjectFile("$name.vue")

    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "as", "data", "method", "only", "on-cancel-token")
  }

  fun testWatchProperty() {
    myFixture.configureVueDependencies(VueTestModule.VUE_2_6_10)
    myFixture.configureByFile("watchProperty.vue")
    myFixture.completeBasic()
    myFixture.type("doubleA")
    myFixture.lookup.let { lookup ->
      lookup.currentItem = lookup.items.find { it.lookupString == "doubleAge." }
    }
    myFixture.type("\n")
    myFixture.type("\n")
    myFixture.checkResultByFile("watchProperty.after.vue")
  }

  fun testNamespacedComponents() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_2_2)
    myFixture.copyDirectoryToProject(getTestName(true), ".")
    myFixture.configureFromTempProjectFile("scriptSetup.vue")
    myFixture.type("Forms.")
    myFixture.completeBasic()
    myFixture.type("Fo\n")
    myFixture.type(".")
    myFixture.completeBasic()
    myFixture.type("In\n :")
    myFixture.completeBasic()
    myFixture.type("fo\n")
    myFixture.checkResultByFile("${getTestName(true)}/scriptSetup_after.vue")
  }

  fun testNoFilterForOnProps() {
    doLookupTest()
  }

  fun testScriptSetupGeneric() {
    doLookupTest(VueTestModule.VUE_3_3_4, locations = listOf(
      "clearable.<caret>", "value.<caret>", "Clearable).<caret>\">", "Clearable).<caret> }}", "foo.<caret>;"))
  }

  fun testDefineModelAttribute() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.configureByText("FooBar.vue", """
      <script setup lang="ts">
      let count = defineModel<number>('count', {default: 0})
      </script>
    """.trimIndent())
    myFixture.configureByText("app.vue", """
      <template>
        <FooBar v-model:<caret>></FooBar>
      </template>
    """.trimIndent())
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "count")
  }

  fun testInjectInLiterals() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(
      myFixture.lookupElementStrings!!,
      "literal",
      "func",
      "computed",
      "funcData",
      "app",
      "scriptSetup",
      "scriptSetupRef",
      "globalProvide",
      "globalProvideRef"
    )
  }

  fun testInjectInLiteralsUnique() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertSize(3, myFixture.lookupElements!!)
    assertSameElements(
      myFixture.lookupElementStrings!!,
      "provideGlobal",
      "provideInApp",
      "provideLiteral",
    )
  }

  fun testInjectInLiteralsUnquoted() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(
      myFixture.lookupElementStrings!!,
      "provideGlobal",
      "provideInApp",
      "provideLiteral",
    )
    assertDoesntContain(myFixture.lookupElementStrings!!, "\"provideGlobal\"")

    myFixture.type("provideIn")
    myFixture.completeBasic()
    myFixture.checkResultByFile("${getTestName(true)}/${getTestName(false)}.after.vue")
  }

  fun testInjectInScriptSetupLiteralsUnquoted() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(
      myFixture.lookupElementStrings!!,
      "provideGlobal",
      "provideInApp",
      "provideLiteral",
    )

    myFixture.type("provideG")
    myFixture.completeBasic()
    myFixture.checkResultByFile("${getTestName(true)}/${getTestName(false)}.after.vue")
  }

  fun testInjectInProperties() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.copyDirectoryToProject(getTestName(true), "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(
      myFixture.lookupElementStrings!!,
      "literal",
      "func",
      "computed",
      "funcData",
      "app",
      "scriptSetup",
      "scriptSetupRef",
      "globalProvide",
      "globalProvideRef"
    )
    assertDoesntContain(myFixture.lookupElementStrings!!, "myInjectionKey")
  }

  fun testDefineSlotsSlotName() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.configureByFile(getTestName(true) + ".vue")
    myFixture.completeBasic()
    assertSameElements(myFixture.lookupElementStrings!!, "default", "header", "footer")
  }

  fun testComponentCustomProperties() {
    myFixture.configureVueDependencies()
    myFixture.copyDirectoryToProject("componentCustomProperties", "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "attributeFromCustomProps")
  }

  fun testComponentCustomPropertiesWithFunctionOverloads() {
    myFixture.configureVueDependencies()
    myFixture.copyDirectoryToProject("componentCustomPropertiesWithFunctionOverloads", "")
    myFixture.configureFromTempProjectFile("${getTestName(false)}.vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "\$methodFromCustomProps")
  }

  fun testDefineSlotsProperties() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.configureByFile(getTestName(true) + ".vue")
    myFixture.completeBasic()
    assertContainsElements(myFixture.lookupElementStrings!!, "year", "age", "companyName", "company-name")
    assertDoesntContain(myFixture.lookupElementStrings!!, "msg", "pageTitle", "page-title")
  }

  fun testDefineExpose() {
    doLookupTest(VueTestModule.VUE_3_3_4, dir = true)
  }

  fun testCompleteComponentWithDefineOptions() {
    myFixture.configureVueDependencies(VueTestModule.VUE_3_3_4)
    myFixture.configureByText("BestFeature.vue", """
      <script setup lang="ts">
      defineOptions({
        name: "BestComponentEver"
      })
      </script>
      <template></template>
    """.trimIndent())
    myFixture.configureByText("Component.vue", """
      <script setup lang="ts">
      import Best<caret>
      </script>
      <template></template>
    """.trimIndent())

    noAutoComplete {
      myFixture.completeBasic()
      assertContainsElements(myFixture.lookupElementStrings!!, "BestComponentEver")
      assertDoesntContain(myFixture.lookupElementStrings!!, "BestFeature")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
      myFixture.checkResult("""
        <script setup lang="ts">
        import BestComponentEver from "./BestFeature.vue";
        </script>
        <template></template>
      """.trimIndent())
    }
  }

  private fun assertDoesntContainVueLifecycleHooks() {
    myFixture.completeBasic()
    assertDoesntContain(myFixture.lookupElementStrings!!, "\$el", "\$options", "\$parent")
  }

  private fun doLookupTest(vararg modules: VueTestModule,
                           fileContents: String? = null,
                           dir: Boolean = false,
                           noConfigure: Boolean = false,
                           locations: List<String> = emptyList(),
                           renderPriority: Boolean = true,
                           renderTypeText: Boolean = true,
                           renderTailText: Boolean = false,
                           containsCheck: Boolean = false,
                           renderProximity: Boolean = false,
                           renderDisplayText: Boolean = false,
                           renderDisplayEffects: Boolean = renderPriority,
                           lookupFilter: (item: LookupElementInfo) -> Boolean = { true }) {
    if (!noConfigure) {
      if (dir) {
        myFixture.copyDirectoryToProject(getTestName(true), ".")
      }
      if (modules.isNotEmpty()) {
        myFixture.configureVueDependencies(*modules)
      }
      if (fileContents != null) {
        myFixture.configureByText(getTestName(true) + ".vue", fileContents)
      }
      else if (dir) {
        myFixture.configureFromTempProjectFile(getTestName(true) + ".vue")
      }
      else {
        myFixture.configureByFile(getTestName(true) + ".vue")
      }
    }
    if (locations.isEmpty()) {
      myFixture.completeBasic()
      myFixture.checkListByFile(
        myFixture.renderLookupItems(renderPriority, renderTypeText, renderTailText, renderProximity, renderDisplayText,
                                    renderDisplayEffects,
                                    lookupFilter),
        getTestName(true) + ".txt",
        containsCheck
      )
    }
    else {
      locations.forEachIndexed { index, location ->
        myFixture.moveToOffsetBySignature(location)
        myFixture.completeBasic()
        myFixture.checkListByFile(
          myFixture.renderLookupItems(renderPriority, renderTypeText, renderTailText, renderProximity, renderDisplayText,
                                      renderDisplayEffects,
                                      lookupFilter),
          getTestName(true) + ".${index + 1}.txt",
          containsCheck
        )
      }
    }
  }
}

private val VUETIFY_UNRESOLVED_COMPONENTS = setOf(
  //grid components
  "v-flex",
  "v-spacer",
  "v-container",
  "v-layout",
  //functional components
  "v-autocomplete",
  "v-bottom-sheet-transition",
  "v-breadcrumbs-divider",
  "v-carousel-reverse-transition",
  "v-carousel-transition",
  "v-dialog-bottom-transition",
  "v-dialog-transition",
  "v-expand-transition",
  "v-fab-transition",
  "v-fade-transition",
  "v-menu",
  "v-menu-transition",
  "v-row-expand-transition",
  "v-select",
  "v-scale-transition",
  "v-scroll-x-reverse-transition",
  "v-scroll-x-transition",
  "v-scroll-y-reverse-transition",
  "v-scroll-y-transition",
  "v-slide-x-reverse-transition",
  "v-slide-x-transition",
  "v-slide-y-reverse-transition",
  "v-slide-y-transition",
  "v-stepper-items",
  "v-tab-item",
  "v-tab-reverse-transition",
  "v-tab-transition",
  "v-table-overflow",
  "v-tabs",
  "v-tabs-items",
  "v-text-field",
  "v-card-actions",
  "v-card-text",
  "v-list-tile-action",
  "v-list-tile-action-text",
  "v-list-tile-content",
  "v-list-tile-sub-title",
  "v-list-tile-title",
  "v-stepper-header",
  "v-toolbar-items",
  "v-toolbar-title"
)
private val VUETIFY_UNRESOLVED_COMPONENTS_WITH_PASCAL_CASE: MutableIterable<String> = ContainerUtil.concat(
  VUETIFY_UNRESOLVED_COMPONENTS,
  VUETIFY_UNRESOLVED_COMPONENTS.map {
    toAsset(it, true)
  })
