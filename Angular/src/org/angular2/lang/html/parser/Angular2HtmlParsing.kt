// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.lang.html.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilder.Marker
import com.intellij.lang.html.HtmlParsing
import com.intellij.lang.javascript.JavaScriptParserBundle
import com.intellij.psi.tree.ICustomParsingType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILazyParseableElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.xml.XmlElementType
import com.intellij.psi.xml.XmlTokenType
import com.intellij.xml.parsing.XmlParserBundle
import com.intellij.xml.util.XmlUtil
import org.angular2.codeInsight.blocks.BLOCK_LET
import org.angular2.lang.Angular2Bundle
import org.angular2.lang.expr.parser.Angular2EmbeddedExprTokenType
import org.angular2.lang.expr.parser.Angular2EmbeddedExprTokenType.Angular2InterpolationExprTokenType
import org.angular2.lang.expr.parser.Angular2EmbeddedExprTokenType.Companion.createTemplateBindings
import org.angular2.lang.html.Angular2TemplateSyntax
import org.angular2.lang.html.lexer.Angular2HtmlTokenTypes
import org.angular2.web.ATTR_NG_NON_BINDABLE

open class Angular2HtmlParsing(private val templateSyntax: Angular2TemplateSyntax, builder: PsiBuilder) : HtmlParsing(builder) {

  fun parseExpansionFormContent() {
    val expansionFormContent = mark()
    var xmlText: Marker? = null
    while (!eof()) {
      when (token()) {
        XmlTokenType.XML_START_TAG_START -> {
          xmlText = terminateText(xmlText)
          parseTag()
          flushIncompleteStackItemsWhile { it is HtmlTagInfo }
        }
        XmlTokenType.XML_PI_START -> {
          xmlText = terminateText(xmlText)
          parseProcessingInstruction()
        }
        XmlTokenType.XML_CHAR_ENTITY_REF, XmlTokenType.XML_ENTITY_REF_TOKEN -> {
          xmlText = startText(xmlText)
          parseReference()
        }
        XmlTokenType.XML_CDATA_START -> {
          xmlText = startText(xmlText)
          parseCData()
        }
        XmlTokenType.XML_COMMENT_START -> {
          xmlText = startText(xmlText)
          parseComment()
        }
        XmlTokenType.XML_BAD_CHARACTER -> {
          xmlText = startText(xmlText)
          val error = mark()
          advance()
          error.error(XmlParserBundle.message("xml.parsing.unescaped.ampersand.or.nonterminated.character.entity.reference"))
        }
        XmlTokenType.XML_END_TAG_START -> {
          val tagEndError = mark()
          advance()
          if (token() === XmlTokenType.XML_NAME) {
            advance()
            if (token() === XmlTokenType.XML_TAG_END) {
              advance()
            }
          }
          tagEndError.error(XmlParserBundle.message("xml.parsing.closing.tag.matches.nothing"))
        }
        is ICustomParsingType, is ILazyParseableElementType -> {
          xmlText = terminateText(xmlText)
          advance()
        }
        else -> {
          if (hasCustomTagContent()) {
            xmlText = parseCustomTagContent(xmlText)
          }
          else {
            xmlText = startText(xmlText)
            advance()
          }
        }
      }
    }
    terminateText(xmlText)
    expansionFormContent.done(Angular2HtmlElementTypes.EXPANSION_FORM_CASE_CONTENT)
  }

  override fun hasCustomTopLevelContent(): Boolean {
    return CUSTOM_CONTENT.contains(token())
  }

  override fun hasCustomTagContent(): Boolean {
    return CUSTOM_CONTENT.contains(token())
  }

  private val hasAngularBlockOnStack: Boolean
    get() {
      var result: AngularBlock? = null
      processStackItems {
        if (it is AngularBlock) {
          result = it
          false
        }
        else true
      }
      return result != null
    }

  override fun parseCustomTagContent(xmlText: Marker?): Marker? {
    var result = xmlText
    when (token()) {
      Angular2HtmlTokenTypes.INTERPOLATION_START -> {
        result = if (!inNgNonBindableContext()) {
          terminateText(result)
        }
        else {
          startText(result)
        }
        val interpolation = mark()
        advance()
        if (token() is Angular2InterpolationExprTokenType) {
          advance()
        }
        if (!inNgNonBindableContext()) {
          if (token() === Angular2HtmlTokenTypes.INTERPOLATION_END) {
            advance()
            interpolation.drop()
          }
          else {
            interpolation.error(Angular2Bundle.message("angular.parse.template.unterminated-interpolation"))
          }
        }
        else {
          if (token() === Angular2HtmlTokenTypes.INTERPOLATION_END) {
            advance()
          }
          interpolation.collapse(XmlTokenType.XML_DATA_CHARACTERS)
        }
      }
      Angular2HtmlTokenTypes.EXPANSION_FORM_START -> {
        result = terminateText(result)
        parseExpansionForm()
      }
      XmlTokenType.XML_COMMA -> {
        result = startText(result)
        builder.remapCurrentToken(XmlTokenType.XML_DATA_CHARACTERS)
        advance()
      }
      XmlTokenType.XML_DATA_CHARACTERS -> {
        result = startText(result)
        val dataStart = mark()
        while (DATA_TOKENS.contains(token())) {
          advance()
        }
        dataStart.collapse(XmlTokenType.XML_DATA_CHARACTERS)
      }
      Angular2HtmlTokenTypes.BLOCK_NAME -> {
        result = terminateText(result)
        parseBlockStart()
      }
      Angular2HtmlTokenTypes.BLOCK_END -> {
        result = terminateText(result)
        if (hasAngularBlockOnStack) {
          advance()
          flushIncompleteStackItemsWhile { it !is AngularBlock }
          completeTopStackItem()
        }
        else {
          builder.error(Angular2Bundle.message("angular.parse.template.unexpected-block-closing-rbrace"))
          advance()
        }
      }
    }
    return result
  }

  private fun parseBlockStart() {
    assert(builder.tokenType == Angular2HtmlTokenTypes.BLOCK_NAME)
    val startMarker = builder.mark()
    val blockName = builder.tokenText!!.removePrefix("@")
    builder.advanceLexer()
    if (blockName == "") {
      startMarker.done(Angular2HtmlElementTypes.BLOCK)
      return
    }
    else if (blockName == BLOCK_LET) {
      parseLetBlock(startMarker)
      return
    }
    if (builder.tokenType == Angular2HtmlTokenTypes.BLOCK_PARAMETERS_START) {
      val parameters = builder.mark()
      builder.advanceLexer()
      val parametersContents = builder.mark()
      var parameterIndex = 0
      while (!builder.eof()) {
        if (builder.tokenType is Angular2EmbeddedExprTokenType) {
          builder.advanceLexer()
          if (builder.tokenType == Angular2HtmlTokenTypes.BLOCK_SEMICOLON) {
            builder.advanceLexer()
          }
        }
        else if (builder.tokenType == Angular2HtmlTokenTypes.BLOCK_SEMICOLON) {
          builder.mark().collapse(Angular2EmbeddedExprTokenType.createBlockParameter(templateSyntax, blockName, parameterIndex))
          builder.advanceLexer()
        }
        else {
          break
        }
        parameterIndex++
      }
      if (builder.eof() || builder.tokenType != Angular2HtmlTokenTypes.BLOCK_PARAMETERS_END) {
        parameters.errorBefore(JavaScriptParserBundle.message("javascript.parser.message.missing.rparen"), parametersContents)
        parametersContents.drop()
        parameters.precede().done(Angular2HtmlElementTypes.BLOCK_PARAMETERS)
      }
      else {
        builder.advanceLexer()
        parametersContents.drop()
        parameters.done(Angular2HtmlElementTypes.BLOCK_PARAMETERS)
      }
    }
    if (builder.tokenType == Angular2HtmlTokenTypes.BLOCK_START) {
      val errorStartMarker = builder.mark()
      builder.advanceLexer()
      val errorEndMarker = builder.mark()
      pushItemToStack(AngularBlock(startMarker, errorStartMarker.precede(), errorStartMarker, errorEndMarker))
    }
    else {
      builder.error(Angular2Bundle.message("angular.parse.template.missing-block-opening-lbrace"))
      startMarker.done(Angular2HtmlElementTypes.BLOCK)
    }
  }

  private fun parseLetBlock(startMarker: Marker) {
    if (builder.tokenType !is Angular2EmbeddedExprTokenType) {
      if (builder.rawLookup(-1) == Angular2HtmlTokenTypes.BLOCK_NAME) {
        builder.error(Angular2Bundle.message("angular.parse.expression.expected-whitespace"))
      }
      else {
        builder.error(JavaScriptParserBundle.message("javascript.parser.message.expected.identifier"))
      }
      startMarker.done(Angular2HtmlElementTypes.BLOCK)
      return
    }
    val parameters = builder.mark()
    builder.advanceLexer()
    if (builder.tokenType != Angular2HtmlTokenTypes.BLOCK_SEMICOLON) {
      builder.error(Angular2Bundle.message("angular.parse.template.missing-let-block-closing-semicolon"))
    }
    else {
      builder.advanceLexer()
    }
    parameters.done(Angular2HtmlElementTypes.BLOCK_PARAMETERS)
    startMarker.done(Angular2HtmlElementTypes.BLOCK)
  }

  override fun parseCustomTopLevelContent(error: Marker?): Marker? {
    val result = flushError(error)
    terminateText(parseCustomTagContent(null))
    return result
  }

  override fun createHtmlTagInfo(originalTagName: String, startMarker: Marker): HtmlTagInfoImpl {
    return AngularHtmlTagInfo(normalizeTagName(originalTagName), originalTagName, startMarker)
  }

  override fun parseAttribute() {
    assert(token() === XmlTokenType.XML_NAME)
    val att = mark()
    val tagName = XmlUtil.findLocalNameByQualifiedName(peekTagInfo().normalizedName)
    val attributeName = builder.tokenText
    if (ATTR_NG_NON_BINDABLE == attributeName) {
      (peekTagInfo() as AngularHtmlTagInfo).hasNgNonBindable = true
    }
    val attributeInfo = Angular2AttributeNameParser.parse(attributeName!!, tagName!!)
    if (attributeInfo.error != null) {
      val attrName = mark()
      advance()
      attrName.error(attributeInfo.error)
    }
    else if (attributeInfo.type == Angular2AttributeType.REFERENCE) {
      val attrName = mark()
      advance()
      attrName.collapse(Angular2HtmlVarAttrTokenType.REFERENCE)
    }
    else if (attributeInfo.type == Angular2AttributeType.LET) {
      val attrName = mark()
      advance()
      attrName.collapse(Angular2HtmlVarAttrTokenType.LET)
    }
    else {
      advance()
    }
    var attributeElementType = attributeInfo.type.elementType
    if (token() === XmlTokenType.XML_EQ) {
      advance()
      attributeElementType = parseAttributeValue(attributeElementType, attributeInfo.name)
    }
    att.done(
      if (attributeElementType !== Angular2HtmlElementTypes.NG_CONTENT_SELECTOR)
        attributeElementType
      else
        XmlElementType.XML_ATTRIBUTE
    )
  }

  private fun parseAttributeValue(attributeElementType: IElementType, name: String): IElementType {
    var result = attributeElementType
    val attValue = mark()
    val contentType = getAttributeContentType(templateSyntax, result, name)
    if (token() === XmlTokenType.XML_ATTRIBUTE_VALUE_START_DELIMITER) {
      advance()
      val contentStart = if (contentType != null) mark() else null
      while (true) {
        val tt = token()
        if (tt == null
            || tt === XmlTokenType.XML_ATTRIBUTE_VALUE_END_DELIMITER
            || tt === XmlTokenType.XML_END_TAG_START
            || tt === XmlTokenType.XML_EMPTY_ELEMENT_END
            || tt === XmlTokenType.XML_START_TAG_START) {
          break
        }
        if (tt is Angular2InterpolationExprTokenType && result === XmlElementType.XML_ATTRIBUTE) {
          result = Angular2HtmlElementTypes.PROPERTY_BINDING
        }
        when (tt) {
          XmlTokenType.XML_BAD_CHARACTER -> {
            val error = mark()
            advance()
            error.error(XmlParserBundle.message("xml.parsing.unescaped.ampersand.or.nonterminated.character.entity.reference"))
          }
          XmlTokenType.XML_ENTITY_REF_TOKEN -> {
            parseReference()
          }
          else -> {
            advance()
          }
        }
      }
      if (contentStart != null) {
        if (contentType === Angular2HtmlElementTypes.NG_CONTENT_SELECTOR) {
          contentStart.done(contentType)
        }
        else {
          contentStart.collapse(contentType!!)
        }
      }
      if (token() === XmlTokenType.XML_ATTRIBUTE_VALUE_END_DELIMITER) {
        advance()
      }
      else {
        error(XmlParserBundle.message("xml.parsing.unclosed.attribute.value"))
      }
    }
    else {
      if (token().let { it !== XmlTokenType.XML_TAG_END && it !== XmlTokenType.XML_EMPTY_ELEMENT_END }) {
        if (contentType != null) {
          val contentStart = mark()
          advance()
          if (contentType === Angular2HtmlElementTypes.NG_CONTENT_SELECTOR) {
            contentStart.done(contentType)
          }
          else {
            contentStart.collapse(contentType)
          }
        }
        else {
          advance() // Single token att value
        }
      }
    }
    attValue.done(XmlElementType.XML_ATTRIBUTE_VALUE)
    return result
  }

  private fun parseExpansionForm() {
    assert(token() === Angular2HtmlTokenTypes.EXPANSION_FORM_START)
    var expansionForm = mark()
    advance()
    if (!remapTokensUntilComma(Angular2EmbeddedExprTokenType.createBindingExpr(templateSyntax)) /*switch value*/
        || !remapTokensUntilComma(XmlTokenType.XML_DATA_CHARACTERS) /*type*/) {
      markCriticalExpansionFormProblem(expansionForm)
      return
    }
    skipRealWhiteSpaces()
    var first = true
    while (token().let { it === XmlTokenType.XML_DATA_CHARACTERS || it === Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_START }) {
      if (!parseExpansionFormCaseContent() && first) {
        markCriticalExpansionFormProblem(expansionForm)
        return
      }
      first = false
      skipRealWhiteSpaces()
    }
    if (token() !== Angular2HtmlTokenTypes.EXPANSION_FORM_END) {
      expansionForm
        .error(Angular2Bundle.message("angular.parse.template.unterminated-expansion-form"))
      expansionForm = expansionForm.precede()
    }
    else {
      advance()
    }
    expansionForm.done(Angular2HtmlElementTypes.EXPANSION_FORM)
  }

  private fun markCriticalExpansionFormProblem(expansionForm: Marker) {
    // critical problem, most likely not an expansion form at all
    expansionForm.rollbackTo()
    val errorMarker = mark()
    assert(token() === Angular2HtmlTokenTypes.EXPANSION_FORM_START)
    advance() //consume LBRACE
    errorMarker.error(Angular2Bundle.message("angular.parse.template.unterminated-expansion-form"))
  }

  private fun remapTokensUntilComma(textType: IElementType): Boolean {
    val start = mark()
    while (!eof() && token() !== XmlTokenType.XML_COMMA) {
      advance()
    }
    start.collapse(textType)
    if (token() !== XmlTokenType.XML_COMMA) {
      start.precede().error(Angular2Bundle.message("angular.parse.template.invalid-icu-message-expected-comma"))
      return false
    }
    advance()
    return true
  }

  private fun parseExpansionFormCaseContent(): Boolean {
    var expansionFormCase = mark()
    if (token() === XmlTokenType.XML_DATA_CHARACTERS) {
      advance() // value
      skipRealWhiteSpaces()
      if (token() !== Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_START) {
        expansionFormCase.error(Angular2Bundle.message("angular.parse.template.invalid-icu-message-expected-left-brace"))
        expansionFormCase.precede().done(Angular2HtmlElementTypes.EXPANSION_FORM_CASE)
        return false
      }
    }
    else if (token() === Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_START) {
      advance()
      expansionFormCase.error(Angular2Bundle.message("angular.parse.template.invalid-icu-message-missing-case-value"))
      expansionFormCase = expansionFormCase.precede()
    }
    else {
      throw IllegalStateException()
    }
    advance()
    val content = mark()
    var level = 1
    var tt: IElementType?
    while (token().also { tt = it } !== Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_END || level > 1) {
      when (tt) {
        Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_START -> {
          level++
        }
        Angular2HtmlTokenTypes.EXPANSION_FORM_CASE_END -> {
          level--
        }
        null -> {
          content.error(Angular2Bundle.message("angular.parse.template.invalid-icu-message-missing-right-brace"))
          expansionFormCase.done(Angular2HtmlElementTypes.EXPANSION_FORM_CASE)
          return false
        }
      }
      advance()
    }
    content.collapse(Angular2ExpansionFormCaseContentTokenType.get(templateSyntax))
    advance()
    expansionFormCase.done(Angular2HtmlElementTypes.EXPANSION_FORM_CASE)
    return true
  }

  private fun skipRealWhiteSpaces() {
    while (token() === XmlTokenType.XML_REAL_WHITE_SPACE) {
      advance()
    }
  }

  private fun inNgNonBindableContext(): Boolean {
    var result = false
    processStackItems {
      if (it is AngularHtmlTagInfo && it.hasNgNonBindable) {
        result = true
        false
      }
      else true
    }
    return result
  }

  private inner class AngularHtmlTagInfo(
    normalizedName: String,
    originalName: String,
    marker: Marker,
    var hasNgNonBindable: Boolean = false,
  ) : HtmlTagInfoImpl(normalizedName, originalName, marker)

  private class AngularBlock(
    private val startMarker: Marker,
    private val contentsMarker: Marker,
    private val errorStartMarker: Marker,
    private val errorEndMarker: Marker,
  ) : HtmlParserStackItem {

    override fun done(
      builder: PsiBuilder,
      beforeMarker: Marker?,
      incomplete: Boolean,
    ) {
      if (incomplete) {
        errorStartMarker.errorBefore(Angular2Bundle.message("angular.parse.template.missing-block-closing-rbrace"), errorEndMarker)
      }
      else {
        errorStartMarker.drop()
      }
      errorEndMarker.drop()
      if (beforeMarker == null) {
        contentsMarker.done(Angular2HtmlElementTypes.BLOCK_CONTENTS)
        startMarker.done(Angular2HtmlElementTypes.BLOCK)
      }
      else {
        contentsMarker.doneBefore(Angular2HtmlElementTypes.BLOCK_CONTENTS, beforeMarker)
        startMarker.doneBefore(Angular2HtmlElementTypes.BLOCK, beforeMarker)
      }
    }

  }

  companion object {
    private val CUSTOM_CONTENT = TokenSet.create(Angular2HtmlTokenTypes.EXPANSION_FORM_START,
                                                 Angular2HtmlTokenTypes.INTERPOLATION_START,
                                                 XmlTokenType.XML_DATA_CHARACTERS,
                                                 XmlTokenType.XML_COMMA,
                                                 Angular2HtmlTokenTypes.BLOCK_NAME,
                                                 Angular2HtmlTokenTypes.BLOCK_END)
    private val DATA_TOKENS = TokenSet.create(XmlTokenType.XML_COMMA, XmlTokenType.XML_DATA_CHARACTERS)
    private fun getAttributeContentType(templateSyntax: Angular2TemplateSyntax, type: IElementType, name: String): IElementType? =
      when (type) {
        Angular2HtmlElementTypes.PROPERTY_BINDING, Angular2HtmlElementTypes.BANANA_BOX_BINDING -> {
          Angular2EmbeddedExprTokenType.createBindingExpr(templateSyntax)
        }
        Angular2HtmlElementTypes.EVENT -> {
          Angular2EmbeddedExprTokenType.createActionExpr(templateSyntax)
        }
        Angular2HtmlElementTypes.TEMPLATE_BINDINGS -> {
          createTemplateBindings(templateSyntax, name)
        }
        Angular2HtmlElementTypes.NG_CONTENT_SELECTOR -> {
          Angular2HtmlElementTypes.NG_CONTENT_SELECTOR
        }
        Angular2HtmlElementTypes.REFERENCE, Angular2HtmlElementTypes.LET, XmlElementType.XML_ATTRIBUTE -> {
          null
        }
        else -> {
          throw IllegalStateException("Unsupported element type: $type")
        }
      }

  }
}