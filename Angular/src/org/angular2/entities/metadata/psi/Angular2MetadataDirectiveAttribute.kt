// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.angular2.entities.metadata.psi

import com.intellij.polySymbols.js.apiStatus
import com.intellij.lang.javascript.psi.JSElementBase
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSType
import com.intellij.model.Pointer
import com.intellij.openapi.util.NullableLazyValue
import com.intellij.openapi.util.NullableLazyValue.lazyNullable
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.utils.coalesceWith
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import com.intellij.util.asSafely
import org.angular2.entities.Angular2DirectiveAttribute
import org.angular2.entities.Angular2EntityUtils

class Angular2MetadataDirectiveAttribute internal constructor(
  private val myOwner: Angular2MetadataDirectiveBase<*>,
  private val myIndex: Int,
  override val name: String,
) : Angular2DirectiveAttribute {

  private val myParameter: NullableLazyValue<JSParameter> = lazyNullable { myOwner.getConstructorParameter(myIndex) }

  override val type: JSType?
    get() = myParameter.value?.jsType

  override val sourceElement: PsiElement
    get() = myParameter.value ?: myOwner.sourceElement

  override val apiStatus: PolySymbolApiStatus
    get() = myParameter.value?.apiStatus.coalesceWith(myOwner.sourceElement.asSafely<JSElementBase>()?.apiStatus)

  override fun toString(): String {
    return Angular2EntityUtils.toString(this)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass != other.javaClass) return false
    val attribute = other as Angular2MetadataDirectiveAttribute?
    return myIndex == attribute!!.myIndex && myOwner == attribute.myOwner && name == attribute.name
  }


  override fun hashCode(): Int {
    var result = myOwner.hashCode()
    result = 31 * result + myIndex
    result = 31 * result + name.hashCode()
    return result
  }

  override fun createPointer(): Pointer<Angular2MetadataDirectiveAttribute> {
    val owner = myOwner.createSmartPointer()
    val index = myIndex
    val name = this.name
    return Pointer {
      owner.dereference()?.let { Angular2MetadataDirectiveAttribute(it, index, name) }
    }
  }
}
