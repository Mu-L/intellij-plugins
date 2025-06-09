package org.angular2.library.forms.scopes

import com.intellij.model.Pointer
import com.intellij.openapi.util.NlsSafe
import com.intellij.polySymbols.*
import com.intellij.polySymbols.js.JS_STRING_LITERALS
import com.intellij.polySymbols.completion.PolySymbolCodeCompletionItem
import com.intellij.polySymbols.patterns.ComplexPatternOptions
import com.intellij.polySymbols.patterns.PolySymbolsPattern
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory.createComplexPattern
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory.createPatternSequence
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory.createStringMatch
import com.intellij.polySymbols.patterns.PolySymbolsPatternFactory.createSymbolReferencePlaceholder
import com.intellij.polySymbols.patterns.PolySymbolsPatternReferenceResolver
import com.intellij.polySymbols.query.PolySymbolsCodeCompletionQueryParams
import com.intellij.polySymbols.query.PolySymbolsListSymbolsQueryParams
import com.intellij.polySymbols.query.PolySymbolsNameMatchQueryParams
import com.intellij.polySymbols.utils.unwrapMatchedSymbols
import com.intellij.util.containers.Stack
import com.intellij.util.containers.map2Array
import org.angular2.library.forms.Angular2FormGroup
import org.angular2.library.forms.NG_FORM_ANY_CONTROL_PROPS
import org.angular2.library.forms.NG_FORM_GROUP_PROPS
import org.angular2.web.Angular2SymbolOrigin

class Angular2FormGroupGetCallLiteralScope(private val formGroup: Angular2FormGroup) : PolySymbolsScope {

  override fun isExclusiveFor(qualifiedKind: PolySymbolQualifiedKind): Boolean =
    qualifiedKind == JS_STRING_LITERALS

  override fun getSymbols(qualifiedKind: PolySymbolQualifiedKind, params: PolySymbolsListSymbolsQueryParams, scope: Stack<PolySymbolsScope>): List<PolySymbolsScope> =
    if (qualifiedKind == JS_STRING_LITERALS)
      listOf(FormGroupGetPathSymbol)
    else
      formGroup.getSymbols(qualifiedKind, params, scope)

  override fun getCodeCompletions(qualifiedName: PolySymbolQualifiedName, params: PolySymbolsCodeCompletionQueryParams, scope: Stack<PolySymbolsScope>): List<PolySymbolCodeCompletionItem> =
    if (qualifiedName.qualifiedKind == JS_STRING_LITERALS)
      super.getCodeCompletions(qualifiedName, params, scope)
        .filter { it.name != "." && (!it.name.endsWith(".") || it.symbol?.unwrapMatchedSymbols()?.lastOrNull()?.qualifiedKind == NG_FORM_GROUP_PROPS) }
    else
      formGroup.getCodeCompletions(qualifiedName, params, scope)

  override fun getMatchingSymbols(qualifiedName: PolySymbolQualifiedName, params: PolySymbolsNameMatchQueryParams, scope: Stack<PolySymbolsScope>): List<PolySymbol> =
    if (qualifiedName.qualifiedKind == JS_STRING_LITERALS)
      super.getMatchingSymbols(qualifiedName, params, scope)
    else
      formGroup.getMatchingSymbols(qualifiedName, params, scope)

  override fun createPointer(): Pointer<out PolySymbolsScope> {
    val formGroupPtr = formGroup.createPointer()
    return Pointer {
      val formGroup = formGroupPtr.dereference() ?: return@Pointer null
      Angular2FormGroupGetCallLiteralScope(formGroup)
    }
  }

  override fun equals(other: Any?): Boolean =
    other === this || (other is Angular2FormGroupGetCallLiteralScope && other.formGroup == formGroup)

  override fun hashCode(): Int =
    formGroup.hashCode()

  override fun getModificationCount(): Long = 0

  companion object {
    object FormGroupGetPathSymbol : PolySymbol {

      override val name: @NlsSafe String
        get() = "FormGroup.get() path"

      override val origin: PolySymbolOrigin
        get() = Angular2SymbolOrigin.empty

      override val qualifiedKind: PolySymbolQualifiedKind
        get() = JS_STRING_LITERALS

      override fun isExclusiveFor(qualifiedKind: PolySymbolQualifiedKind): Boolean =
        qualifiedKind == JS_STRING_LITERALS

      override val pattern: PolySymbolsPattern?
        get() = createComplexPattern(
          ComplexPatternOptions(symbolsResolver = PolySymbolsPatternReferenceResolver(
            *NG_FORM_ANY_CONTROL_PROPS.map2Array {
              PolySymbolsPatternReferenceResolver.Reference(qualifiedKind = it)
            }
          )),
          false,
          createPatternSequence(
            createSymbolReferencePlaceholder(),
            createComplexPattern(
              ComplexPatternOptions(
                symbolsResolver = PolySymbolsPatternReferenceResolver(
                  *NG_FORM_ANY_CONTROL_PROPS.map2Array {
                    PolySymbolsPatternReferenceResolver.Reference(qualifiedKind = it)
                  }
                ),
                repeats = true,
                isRequired = false,
              ),
              false,
              createPatternSequence(
                createStringMatch("."),
                PolySymbolsPatternFactory.createCompletionAutoPopup(false),
                createSymbolReferencePlaceholder(),
              )
            )
          )
        )

      override fun createPointer(): Pointer<out PolySymbol> =
        Pointer.hardPointer(this)

    }
  }

}