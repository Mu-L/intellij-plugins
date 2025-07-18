// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.lang.typescript.service.classic

import com.intellij.lang.javascript.service.JSLanguageServiceUtil
import com.intellij.lang.javascript.service.protocol.JSLanguageServiceAnswer
import com.intellij.lang.javascript.service.protocol.LocalFilePath
import com.intellij.lang.typescript.compiler.TypeScriptCompilerSettings
import com.intellij.lang.typescript.compiler.languageService.protocol.TypeScriptServiceStandardOutputProtocol
import com.intellij.lang.typescript.compiler.languageService.protocol.commands.TypeScriptServiceInitialStateObject
import com.intellij.openapi.project.Project
import java.util.function.Consumer

internal class VueTypeScriptServiceProtocol(
  project: Project,
  settings: TypeScriptCompilerSettings,
  eventConsumer: Consumer<in JSLanguageServiceAnswer>,
  serviceName: String,
  tsServicePath: String,
) : TypeScriptServiceStandardOutputProtocol(project, settings, eventConsumer, serviceName, tsServicePath) {

  override fun createState(): TypeScriptServiceInitialStateObject {
    val state = super.createState()
    state.pluginName = "vueTypeScript"
    return state
  }

  override fun getGlobalPlugins(): List<String> {
    val globalPlugins = super.getGlobalPlugins()
    return globalPlugins + "ws-typescript-vue-plugin"
  }

  override fun getProbeLocations(): Array<LocalFilePath> {
    val probeLocations = super.getProbeLocations()
    val pluginProbe = JSLanguageServiceUtil.getPluginDirectory(this::class.java,
                                                               "vue-service/node_modules/ws-typescript-vue-plugin")!!.parentFile.parentFile.path

    val element = LocalFilePath.create(pluginProbe) ?: return probeLocations
    return probeLocations + element
  }
}
