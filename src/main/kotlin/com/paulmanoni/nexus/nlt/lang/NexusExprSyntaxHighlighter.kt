package com.paulmanoni.nexus.nlt.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IElementType

/**
 * SyntaxHighlighter for NexusExpr — token → text attribute map.
 * Uses DefaultLanguageHighlighterColors so the colors track
 * whatever theme the user has active (Darcula, light, etc.) and
 * stay consistent with how Go/JS/Python expressions are coloured
 * elsewhere in the IDE.
 */
class NexusExprSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = NexusExprLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        if (tokenType == null) return emptyArray()
        val key = MAP[tokenType] ?: return emptyArray()
        return arrayOf(key)
    }

    companion object {
        private val MAP: Map<IElementType, TextAttributesKey> = mapOf(
            NexusExprTokens.KEYWORD to DefaultLanguageHighlighterColors.KEYWORD,
            NexusExprTokens.IDENT to DefaultLanguageHighlighterColors.IDENTIFIER,
            NexusExprTokens.NUMBER to DefaultLanguageHighlighterColors.NUMBER,
            NexusExprTokens.STRING to DefaultLanguageHighlighterColors.STRING,
            NexusExprTokens.OPERATOR to DefaultLanguageHighlighterColors.OPERATION_SIGN,
            NexusExprTokens.PUNCT to DefaultLanguageHighlighterColors.BRACES,
            NexusExprTokens.DOT to DefaultLanguageHighlighterColors.DOT,
            NexusExprTokens.BAD to DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE,
        )
    }
}

class NexusExprSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        NexusExprSyntaxHighlighter()
}