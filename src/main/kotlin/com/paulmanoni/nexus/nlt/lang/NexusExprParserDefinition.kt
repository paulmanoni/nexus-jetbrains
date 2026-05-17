package com.paulmanoni.nexus.nlt.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Minimum-viable ParserDefinition for NexusExpr.
 *
 * The parser is intentionally trivial: it consumes the entire
 * token stream into a single root node. No grammar, no precedence,
 * no error recovery — we're not modelling structure, only making
 * the platform happy so the lexer + highlighter pass can run.
 *
 * Whitespace-only token sets get the platform's standard
 * treatment (skipped in PSI tree); comments are empty because
 * we don't have a comment syntax.
 */
class NexusExprParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = NexusExprLexer()

    override fun createParser(project: Project?): PsiParser = NexusExprParser()

    override fun getFileNodeType(): IFileElementType = NexusExprTokens.FILE

    override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

    override fun getStringLiteralElements(): TokenSet =
        TokenSet.create(NexusExprTokens.STRING)

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = NexusExprFile(viewProvider)
}

class NexusExprFile(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, NexusExprLanguage.INSTANCE) {
    override fun getFileType() = NexusExprFileType
    override fun toString(): String = "NexusExpr file"
}

private class NexusExprParser : PsiParser {
    override fun parse(root: com.intellij.psi.tree.IElementType, builder: com.intellij.lang.PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}