package com.paulmanoni.nexus.nlt.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-rolled lexer for NexusExpr. Tokenizes the entire buffer
 * up front into a flat list — the inputs are short (attribute
 * values, rarely over 100 chars) so the simplicity beats
 * incremental lexing.
 *
 * Recognized tokens (one-pass scan):
 *
 *   whitespace            run of space/tab/newline
 *   keyword               true, false, nil, in, len
 *   ident                 [A-Za-z_][A-Za-z0-9_]*
 *   number                [0-9]+(\.[0-9]+)?
 *   string                "…" with backslash escapes
 *   dot                   .                 (split from ident for clarity)
 *   operator              + - * / % ! = < > == != <= >= && ||
 *   punct                 ( ) [ ] , :
 *   bad                   anything else — keeps highlighter
 *                         from crashing on garbage input
 *
 * Errors are tolerated everywhere — unclosed strings, half-typed
 * operators, etc. The user is in the middle of typing; we don't
 * fight that.
 */
class NexusExprLexer : LexerBase() {

    private data class Tok(val type: IElementType, val start: Int, val end: Int)

    private var buf: CharSequence = ""
    private var bufStart: Int = 0
    private var bufEnd: Int = 0
    private var tokens: List<Tok> = emptyList()
    private var idx: Int = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        bufStart = startOffset
        bufEnd = endOffset
        tokens = tokenize(buffer, startOffset, endOffset)
        idx = 0
    }

    override fun getState(): Int = 0
    override fun getBufferSequence(): CharSequence = buf
    override fun getBufferEnd(): Int = bufEnd

    override fun getTokenType(): IElementType? =
        if (idx >= tokens.size) null else tokens[idx].type

    override fun getTokenStart(): Int = tokens[idx].start
    override fun getTokenEnd(): Int = tokens[idx].end

    override fun advance() {
        idx++
    }

    private fun tokenize(s: CharSequence, start: Int, end: Int): List<Tok> {
        val out = ArrayList<Tok>(16)
        var i = start
        while (i < end) {
            val c = s[i]
            when {
                c.isWhitespace() -> {
                    val j = scanWhile(s, i, end) { it.isWhitespace() }
                    out += Tok(NexusExprTokens.WHITESPACE, i, j)
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    val j = scanWhile(s, i + 1, end) { it.isLetterOrDigit() || it == '_' }
                    val text = s.subSequence(i, j).toString()
                    val type = if (text in KEYWORDS) NexusExprTokens.KEYWORD else NexusExprTokens.IDENT
                    out += Tok(type, i, j)
                    i = j
                }
                c.isDigit() -> {
                    var j = scanWhile(s, i + 1, end) { it.isDigit() }
                    if (j < end && s[j] == '.') {
                        // Lookahead: only consume the dot if a digit
                        // follows it. Otherwise leave the dot to its
                        // own token (so "Posts.length" doesn't slurp
                        // the dot into a malformed number).
                        if (j + 1 < end && s[j + 1].isDigit()) {
                            j = scanWhile(s, j + 1, end) { it.isDigit() }
                        }
                    }
                    out += Tok(NexusExprTokens.NUMBER, i, j)
                    i = j
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < end && s[j] != '"') {
                        if (s[j] == '\\' && j + 1 < end) j++
                        j++
                    }
                    if (j < end) j++ // consume closing quote when present
                    out += Tok(NexusExprTokens.STRING, i, j)
                    i = j
                }
                c == '.' -> {
                    out += Tok(NexusExprTokens.DOT, i, i + 1)
                    i++
                }
                c in PUNCT_CHARS -> {
                    out += Tok(NexusExprTokens.PUNCT, i, i + 1)
                    i++
                }
                else -> {
                    val two = if (i + 1 < end) "$c${s[i + 1]}" else ""
                    if (two in TWO_CHAR_OPS) {
                        out += Tok(NexusExprTokens.OPERATOR, i, i + 2)
                        i += 2
                    } else if (c in SINGLE_CHAR_OPS) {
                        out += Tok(NexusExprTokens.OPERATOR, i, i + 1)
                        i++
                    } else {
                        out += Tok(NexusExprTokens.BAD, i, i + 1)
                        i++
                    }
                }
            }
        }
        return out
    }

    private inline fun scanWhile(s: CharSequence, from: Int, end: Int, pred: (Char) -> Boolean): Int {
        var j = from
        while (j < end && pred(s[j])) j++
        return j
    }

    companion object {
        private val KEYWORDS = setOf("true", "false", "nil", "in", "len")
        private val TWO_CHAR_OPS = setOf("==", "!=", "<=", ">=", "&&", "||")
        private val SINGLE_CHAR_OPS = setOf('+', '-', '*', '/', '%', '<', '>', '=', '!')
        private val PUNCT_CHARS = setOf('(', ')', '[', ']', ',', ':', ';')
    }
}