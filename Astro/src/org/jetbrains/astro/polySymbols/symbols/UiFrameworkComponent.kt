// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.astro.polySymbols.symbols

import com.intellij.model.Pointer
import com.intellij.polySymbols.*
import com.intellij.polySymbols.html.HTML_ATTRIBUTES
import com.intellij.polySymbols.query.PolySymbolsNameMatchQueryParams
import com.intellij.polySymbols.search.PsiSourcedPolySymbol
import com.intellij.polySymbols.utils.PolySymbolsScopeWithCache
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.Stack
import org.jetbrains.astro.AstroFramework
import org.jetbrains.astro.polySymbols.AstroProximity
import org.jetbrains.astro.polySymbols.PROP_ASTRO_PROXIMITY
import org.jetbrains.astro.polySymbols.UI_FRAMEWORK_COMPONENTS
import org.jetbrains.astro.polySymbols.UI_FRAMEWORK_COMPONENT_PROPS

// Currently, we don't support detection of props for components of other UI frameworks and use this
// symbol as a wildcard for all components that aren't from Astro. Once we implement an extension point
// for other frameworks to contribute Astro symbols, the logic of how we handle this will change.

class UiFrameworkComponent(
  override val name: String,
  override val source: PsiElement,
  override val priority: PolySymbol.Priority = PolySymbol.Priority.HIGH,
) : PsiSourcedPolySymbol, PolySymbolsScopeWithCache<PsiElement, Unit>(AstroFramework.ID, source.project, source, Unit) {
  override fun getMatchingSymbols(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolsNameMatchQueryParams,
    scope: Stack<PolySymbolsScope>,
  ): List<PolySymbol> =
    if (qualifiedName.matches(HTML_ATTRIBUTES) && name.contains(":"))
      emptyList()
    else
      super<PolySymbolsScopeWithCache>.getMatchingSymbols(qualifiedName, params, scope)

  override fun provides(qualifiedKind: PolySymbolQualifiedKind): Boolean =
    qualifiedKind == UI_FRAMEWORK_COMPONENT_PROPS

  override fun initialize(consumer: (PolySymbol) -> Unit, cacheDependencies: MutableSet<Any>) {
    cacheDependencies.add(PsiModificationTracker.MODIFICATION_COUNT)
    consumer(AstroComponentWildcardAttribute)
  }

  override val origin: PolySymbolOrigin
    get() = AstroProjectSymbolOrigin

  override val qualifiedKind: PolySymbolQualifiedKind
    get() = UI_FRAMEWORK_COMPONENTS

  override val properties: Map<String, Any>
    get() = mapOf(PROP_ASTRO_PROXIMITY to AstroProximity.LOCAL)

  override fun createPointer(): Pointer<out UiFrameworkComponent> {
    val name = name
    val sourcePtr = source.createSmartPointer()
    return Pointer {
      sourcePtr.dereference()?.let { UiFrameworkComponent(name, it) }
    }
  }

  override fun getModificationCount() = super<PolySymbolsScopeWithCache>.getModificationCount()
}