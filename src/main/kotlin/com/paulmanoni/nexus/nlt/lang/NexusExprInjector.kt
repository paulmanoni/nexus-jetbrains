package com.paulmanoni.nexus.nlt.lang

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Injects NexusExprLanguage into the inner text of expression-
 * bearing attribute values inside .nlt files. The platform then
 * runs NexusExprLexer + SyntaxHighlighter over the injected
 * range, coloring identifiers, keywords (true / false / nil /
 * in / len), operators, strings, numbers, and dots distinctly —
 * the "look like code" effect the user wanted in the quoted
 * value of nl-for / nl-if / :foo / nl-model / etc.
 *
 * Scope-limited: only .nlt files, only the documented
 * expression-bearing attribute names. Regular HTML attribute
 * values (like class="…") stay plain strings.
 *
 * Handler-name attributes (@click="like", nl-on:click="like")
 * are intentionally skipped — their value is a single handler
 * identifier, not an expression, so the highlighter would just
 * paint it as one IDENT and add no value.
 */
class NexusExprInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(XmlAttributeValue::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is XmlAttributeValue) return
        if (context !is PsiLanguageInjectionHost) return
        // Java getter syntax bypasses the Kotlin extension-property
        // resolution that's otherwise ambiguous between
        // XmlAttributeValue.containingFile, PsiElement.containingFile,
        // and PsiLanguageInjectionHost.containingFile (each is
        // defined as an extension and the intersection type matches
        // all three).
        val file = context.getContainingFile() ?: return
        if (!file.name.endsWith(".nlt")) return
        val attr = context.getParent() as? XmlAttribute ?: return
        if (!isExpressionAttribute(attr.name)) return

        // XmlAttributeValue's text includes the surrounding quotes;
        // the inner range is one character in on each side. Skip if
        // the value is shorter than 2 chars (defensive — happens
        // with `attr="` partial input).
        val full = context.getTextLength()
        if (full < 2) return
        val inner = TextRange(1, full - 1)

        registrar.startInjecting(NexusExprLanguage.INSTANCE)
        registrar.addPlace(null, null, context, inner)
        registrar.doneInjecting()
    }
}

private fun isExpressionAttribute(name: String): Boolean = when {
    // Structural directives whose value is an expression.
    name == "nl-if" || name == "nl-else-if" || name == "nl-for" -> true
    name == "nl-show" || name == "nl-model" || name == "nl-html" || name == "nl-text" -> true
    // Bind attributes — short and long form.
    name.startsWith(":") || name.startsWith("nl-bind:") -> true
    // Handlers and slot names are NOT expressions (handler names
    // and slot names respectively), so skip them.
    else -> false
}