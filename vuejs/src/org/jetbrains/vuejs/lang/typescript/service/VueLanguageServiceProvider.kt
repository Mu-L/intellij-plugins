// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.lang.typescript.service

import com.intellij.lang.typescript.compiler.TypeScriptService
import com.intellij.lang.typescript.compiler.languageService.TypeScriptLanguageServiceUtil
import com.intellij.lang.typescript.languageService.TypeScriptServiceProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.vuejs.lang.html.isVueFile
import org.jetbrains.vuejs.lang.typescript.service.classic.VueClassicTypeScriptService
import org.jetbrains.vuejs.lang.typescript.service.lsp.VueLspTypeScriptService

internal class VueLanguageServiceProvider(project: Project) : TypeScriptServiceProvider() {
  private val classicLanguageService by lazy(LazyThreadSafetyMode.PUBLICATION) { project.service<VueClassicServiceWrapper>() }
  private val lspLanguageService by lazy(LazyThreadSafetyMode.PUBLICATION) { project.service<VueLspServiceWrapper>() }

  override val allServices: List<TypeScriptService>
    get() = listOf(classicLanguageService.service, lspLanguageService.service)

  override fun isHighlightingCandidate(file: VirtualFile): Boolean {
    return TypeScriptLanguageServiceUtil.isJavaScriptOrTypeScriptFileType(file.fileType)
           || file.isVueFile
  }
}

@Service(Service.Level.PROJECT)
private class VueClassicServiceWrapper(project: Project) : Disposable {
  val service = VueClassicTypeScriptService(project)

  override fun dispose() {
    Disposer.dispose(service)
  }
}

@Service(Service.Level.PROJECT)
private class VueLspServiceWrapper(project: Project) : Disposable {
  val service = VueLspTypeScriptService(project)

  override fun dispose() {
    Disposer.dispose(service)
  }
}