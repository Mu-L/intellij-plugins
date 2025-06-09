// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.web.symbols

import com.intellij.javascript.polySymbols.symbols.getJSPropertySymbols
import com.intellij.javascript.polySymbols.symbols.getMatchingJSPropertySymbols
import com.intellij.model.Pointer
import com.intellij.util.containers.Stack
import com.intellij.polySymbols.*
import com.intellij.polySymbols.js.JS_PROPERTIES
import com.intellij.polySymbols.query.PolySymbolsListSymbolsQueryParams
import com.intellij.polySymbols.query.PolySymbolsNameMatchQueryParams
import org.jetbrains.vuejs.model.VueComponent
import org.jetbrains.vuejs.model.VueProperty

abstract class VuePropertySymbol<T : VueProperty>(
  item: T,
  owner: VueComponent,
  origin: PolySymbolOrigin,
) : VueNamedPolySymbol<T>(item, owner, origin) {

  abstract override fun createPointer(): Pointer<out VuePropertySymbol<T>>

  override fun isExclusiveFor(qualifiedKind: PolySymbolQualifiedKind): Boolean =
    qualifiedKind == JS_PROPERTIES

  override fun getMatchingSymbols(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolsNameMatchQueryParams,
    scope: Stack<PolySymbolsScope>,
  ): List<PolySymbol> =
    getMatchingJSPropertySymbols(qualifiedName, params.queryExecutor.namesProvider)

  override fun getSymbols(
    qualifiedKind: PolySymbolQualifiedKind,
    params: PolySymbolsListSymbolsQueryParams,
    scope: Stack<PolySymbolsScope>,
  ): List<PolySymbolsScope> =
    getJSPropertySymbols(qualifiedKind)

}