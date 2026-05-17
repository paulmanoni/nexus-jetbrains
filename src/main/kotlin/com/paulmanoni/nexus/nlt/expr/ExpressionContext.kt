package com.paulmanoni.nexus.nlt.expr

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * ExpressionContext represents "the user is sitting in an
 * expression at this position." Where the expression came from
 * (text interpolation vs attribute value vs nl-for split) is
 * captured in `kind` so the resolver can pick the right scope
 * rules without re-parsing.
 */
data class ExpressionContext(
    val kind: Kind,
    /** Full expression text, e.g. "post.author." or "len(Posts)". */
    val text: String,
    /** Caret position within text (0-based). */
    val caretInText: Int,
    /** Document offset where `text` begins — used by callers to
     *  set a custom prefix matcher for completion. */
    val textStartOffset: Int,
) {
    enum class Kind {
        Interpolation,   // {{ … }}
        AttributeValue,  // :foo="…", nl-if="…", etc.
        LoopIter,        // RHS of "x in Xs" — needs an iterable type
        LoopVarSource,   // pseudo-context: when looking up a loop var's type
        EventHandler,    // value of @ev="…" / nl-on:ev="…" (single method name)
    }

    /** Substring of text from start up to caret — used for chain parsing. */
    val typed: String get() = text.substring(0, caretInText)
}

/**
 * detect returns the expression context at a caret position, or
 * null if the caret isn't inside a recognized expression site.
 *
 * Recognized sites:
 *  - {{ expression }} anywhere in HTML text
 *  - value of an attribute whose name starts with `:`, `@`, `#`,
 *    `nl-bind:`, `nl-on:`, `nl-slot:`, or is one of nl-if /
 *    nl-else-if / nl-show / nl-model / nl-html / nl-text / nl-for
 *  - nl-for is handled specially — the value splits on " in " and
 *    only the RHS is parsed as an expression; the LHS is a binding
 *    name and gets no completion.
 *
 * The returned context carries enough info for the completion
 * contributor to (a) compute the caret-relative chain, (b)
 * override the prefix matcher to a literal of the typed segment.
 */
fun detectExpressionContext(element: PsiElement, caretOffset: Int): ExpressionContext? {
    return detectAttribute(element, caretOffset) ?: detectInterpolation(element, caretOffset)
}

private fun detectAttribute(element: PsiElement, caretOffset: Int): ExpressionContext? {
    val valueElem = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java) ?: return null
    val attr = PsiTreeUtil.getParentOfType(valueElem, XmlAttribute::class.java) ?: return null
    val attrName = attr.name

    // valueElem.value strips the surrounding quotes; the unquoted
    // text starts one character past the opening quote.
    val unquoted = valueElem.value ?: return null
    val unquotedStart = valueElem.textRange.startOffset + 1
    val caretInUnquoted = caretOffset - unquotedStart
    if (caretInUnquoted < 0 || caretInUnquoted > unquoted.length) return null

    // Event handlers: value is a bare Go method name. Modifiers
    // (.prevent, .stop, .once) attach to the attribute NAME, not
    // the value, so the value is always just the handler ident.
    // Strip a leading dot-modifier off the value for the typed
    // text we report (rare — usually the value is just the
    // handler name with no extras).
    if (attrName.startsWith("@") || attrName.startsWith("nl-on:")) {
        return ExpressionContext(
            kind = ExpressionContext.Kind.EventHandler,
            text = unquoted,
            caretInText = caretInUnquoted,
            textStartOffset = unquotedStart,
        )
    }

    if (!isExpressionAttribute(attrName)) return null

    if (attrName == "nl-for") {
        // Value is "x in Xs". Caret on the LHS gets no completion
        // (it's a binding name being declared). Caret on the RHS
        // resolves against the component scope to produce an
        // iterable type.
        val inIdx = unquoted.indexOf(" in ")
        if (inIdx >= 0 && caretInUnquoted > inIdx + 4) {
            val rhsStart = inIdx + 4
            return ExpressionContext(
                kind = ExpressionContext.Kind.LoopIter,
                text = unquoted.substring(rhsStart),
                caretInText = caretInUnquoted - rhsStart,
                textStartOffset = unquotedStart + rhsStart,
            )
        }
        return null
    }

    return ExpressionContext(
        kind = ExpressionContext.Kind.AttributeValue,
        text = unquoted,
        caretInText = caretInUnquoted,
        textStartOffset = unquotedStart,
    )
}

private fun isExpressionAttribute(name: String): Boolean = when {
    name == "nl-for" -> true
    name == "nl-if" || name == "nl-else-if" -> true
    name == "nl-show" || name == "nl-model" || name == "nl-html" || name == "nl-text" -> true
    name.startsWith(":") || name.startsWith("nl-bind:") -> true
    else -> false
}

private fun detectInterpolation(element: PsiElement, caretOffset: Int): ExpressionContext? {
    val file = element.containingFile ?: return null
    if (!file.name.endsWith(".nlt")) return null
    val doc: Document = file.viewProvider.document ?: return null
    val text = doc.charsSequence

    // Walk back from caret looking for {{; bail if we hit }} first
    // or run past the document start.
    var i = caretOffset
    while (i >= 2) {
        val pair = text.subSequence(i - 2, i).toString()
        if (pair == "}}") return null
        if (pair == "{{") break
        i--
    }
    if (i < 2) return null
    val openEnd = i // position just past "{{"

    // Walk forward from openEnd looking for }}.
    var j = openEnd
    while (j < text.length - 1) {
        val pair = text.subSequence(j, j + 2).toString()
        if (pair == "}}") break
        j++
    }
    if (j >= text.length - 1) return null

    val raw = text.subSequence(openEnd, j).toString()
    // Trim leading whitespace because that's what the runtime does
    // (TrimSpace in interpret.go). The caret-in-text offset must
    // adjust by the number of leading spaces stripped.
    val leading = raw.takeWhile { it.isWhitespace() }.length
    val expr = raw.trim()
    val caretInExpr = caretOffset - openEnd - leading
    if (caretInExpr < 0 || caretInExpr > expr.length) return null

    return ExpressionContext(
        kind = ExpressionContext.Kind.Interpolation,
        text = expr,
        caretInText = caretInExpr,
        textStartOffset = openEnd + leading,
    )
}