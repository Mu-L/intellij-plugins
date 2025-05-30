// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.web.symbols

import com.intellij.lang.javascript.psi.JSType
import com.intellij.model.Pointer
import com.intellij.polySymbols.*
import com.intellij.polySymbols.patterns.PolySymbolsPattern
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory

class VueAnySymbol(
  override val origin: PolySymbolOrigin,
  qualifiedKind: PolySymbolQualifiedKind,
  override val name: String,
  override val type: JSType? = null,
) : PolySymbol {

  override val namespace: SymbolNamespace = qualifiedKind.namespace

  override val kind: SymbolKind = qualifiedKind.kind

  override val pattern: PolySymbolsPattern
    get() = PolySymbolsPatternFactory.createRegExMatch(".*", false)

  override val properties: Map<String, Any> =
    mapOf(PolySymbol.PROP_HIDE_FROM_COMPLETION to true,
          PolySymbol.PROP_DOC_HIDE_PATTERN to true)

  override fun createPointer(): Pointer<VueAnySymbol> =
    Pointer.hardPointer(this)
}