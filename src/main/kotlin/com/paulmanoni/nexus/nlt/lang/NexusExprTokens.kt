package com.paulmanoni.nexus.nlt.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType

/**
 * Token + element type constants for NexusExpr. Each token type
 * is a singleton IElementType bound to NexusExprLanguage; the
 * lexer hands these back from getTokenType().
 *
 * KEYWORD covers Go-flavored built-ins the runtime understands
 * (true, false, nil, in, len). IDENT is everything else that
 * fits a Go identifier shape. OPERATOR groups single- and
 * two-char arithmetic / comparison / logical operators. PUNCT
 * is for grouping/separator characters (parens, brackets,
 * commas). DOT is split out so the highlighter can colour it
 * consistently with the platform's "dot" attribute.
 */
object NexusExprTokens {
    val KEYWORD: IElementType = NexusExprElementType("KEYWORD")
    val IDENT: IElementType = NexusExprElementType("IDENT")
    val NUMBER: IElementType = NexusExprElementType("NUMBER")
    val STRING: IElementType = NexusExprElementType("STRING")
    val OPERATOR: IElementType = NexusExprElementType("OPERATOR")
    val PUNCT: IElementType = NexusExprElementType("PUNCT")
    val DOT: IElementType = NexusExprElementType("DOT")
    val WHITESPACE: IElementType = NexusExprElementType("WHITESPACE")
    val BAD: IElementType = NexusExprElementType("BAD")

    val FILE: IFileElementType = IFileElementType(NexusExprLanguage.INSTANCE)
}

private class NexusExprElementType(debugName: String) : IElementType(debugName, NexusExprLanguage.INSTANCE)