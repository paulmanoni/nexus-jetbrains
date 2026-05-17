package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.xml.XmlTag

/**
 * Completion for .nlt single-file components. Adds Vue-style
 * nl-* directives, shorthand prefixes (`:`, `@`, `#`), and common
 * event names to the HTML attribute-name completion that .nlt
 * files already inherit by being mapped to HtmlFileType.
 *
 * Scope is bounded by file extension: the contributor returns
 * immediately on non-.nlt files so regular HTML editing is not
 * polluted with framework-specific suggestions.
 *
 * The directive catalog mirrors live/template/parse.go's
 * knownDirectives map. Keep them in sync — adding a directive
 * server-side without updating this list silently drops
 * autocomplete; the typo-suggestion fallback in the parser still
 * works at compile time but discoverability suffers.
 *
 * **Pattern + prefix-matcher note**: IntelliJ's default prefix
 * matcher treats `-`, `:`, `@`, `#` as word separators, so a user
 * typing `nl-` would get an empty effective prefix and our
 * elements would either all show (noisy) or be sorted wrong. We
 * explicitly recompute the prefix by walking back from the caret
 * through non-whitespace, non-bracket characters, then re-issue
 * the CompletionResultSet with PlainPrefixMatcher — that one does
 * literal startsWith matching and respects every character.
 *
 * The pattern is `psiElement().inside(XmlTag)` (broad on purpose)
 * because the parser builds different PSI shapes for partial input
 * — sometimes XmlAttribute, sometimes a bare XML_NAME under
 * XmlTag — and we want completion in all of them.
 */
class NltCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlTag::class.java),
            NltAttributeCompletionProvider(),
        )
    }
}

private val structuralDirectives = listOf(
    "nl-if" to "conditional branch (expression)",
    "nl-else-if" to "alternate conditional (expression)",
    "nl-else" to "fallback branch (no value)",
    "nl-for" to "loop binding — \"x in xs\"",
    "nl-show" to "toggle CSS display (expression)",
    "nl-model" to "two-way binding (bare ident or chain)",
    "nl-html" to "unescaped HTML output (expression)",
    "nl-text" to "text content (expression)",
    "nl-once" to "render-time hint, never re-renders",
    "nl-pre" to "skip directive processing in subtree",
)

private val prefixDirectives = listOf(
    "nl-bind:" to "dynamic attribute binding — long form of `:attr`",
    "nl-on:" to "event handler — long form of `@event`",
    "nl-slot:" to "named slot — long form of `#name`",
)

private val shorthand = listOf(
    ":" to "shorthand for nl-bind: — dynamic attribute",
    "@" to "shorthand for nl-on: — event handler",
    "#" to "shorthand for nl-slot: — named slot",
)

// Mirrors the client JS's DELEGATED_EVENTS array (nexus-live.js)
// so completion offers only events the runtime actually wires up.
private val commonEvents = listOf(
    "click", "input", "change", "submit",
    "keydown", "keyup",
    "blur", "focus",
    "mouseenter", "mouseleave",
)

private val eventModifiers = listOf(
    "prevent" to "preventDefault() before sending",
    "stop" to "stopPropagation()",
    "once" to "fire once, then unbind",
)

private val modelModifiers = listOf(
    "lazy" to "bind on change instead of input",
    "trim" to "strings.TrimSpace before assign",
    "number" to "parse value as number before assign",
)

private class NltAttributeCompletionProvider :
    com.intellij.codeInsight.completion.CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: com.intellij.util.ProcessingContext,
        result: CompletionResultSet,
    ) {
        // Scope-limit: only .nlt files. Regular HTML editing is
        // untouched. The pattern fired inside any XmlTag, so this
        // check is the real filter.
        val fileName = parameters.originalFile.name
        if (!fileName.endsWith(".nlt")) return

        // Recompute the prefix ourselves — IntelliJ's default matcher
        // splits on -, :, @, # and would never match "nl-if" against
        // a typed "nl-".
        val prefix = computePrefix(parameters)
        val r = result.withPrefixMatcher(PlainPrefixMatcher(prefix))

        // After "nl-on:" or "@", offer event names. After ".", offer
        // event modifiers. After "nl-model.", offer model modifiers.
        when {
            prefix.startsWith("@") || prefix.startsWith("nl-on:") -> {
                val opener = if (prefix.startsWith("@")) "@" else "nl-on:"
                if (!prefix.contains('.')) {
                    // Still typing the event name.
                    for (evt in commonEvents) {
                        r.addElement(
                            LookupElementBuilder.create("$opener$evt")
                                .withTypeText("event")
                                .withInsertHandler { ctx, _ -> insertValueQuotes(ctx.editor, ctx.tailOffset) },
                        )
                    }
                } else {
                    // Modifier completion (@click.prev|, etc.) — synthesize
                    // the full string so the PlainPrefixMatcher extends
                    // from where the user already typed.
                    val base = prefix.substringBeforeLast('.')
                    for ((mod, desc) in eventModifiers) {
                        r.addElement(
                            LookupElementBuilder.create("$base.$mod")
                                .withTypeText(desc)
                                .withInsertHandler { ctx, _ -> insertValueQuotes(ctx.editor, ctx.tailOffset) },
                        )
                    }
                }
                return
            }

            prefix.startsWith("nl-model.") -> {
                val base = prefix.substringBeforeLast('.')
                for ((mod, desc) in modelModifiers) {
                    r.addElement(
                        LookupElementBuilder.create("$base.$mod")
                            .withTypeText(desc)
                            .withInsertHandler { ctx, _ -> insertValueQuotes(ctx.editor, ctx.tailOffset) },
                    )
                }
                return
            }
        }

        // Default attribute-name completion: structural directives,
        // prefix directives, shorthand openers.
        for ((name, desc) in structuralDirectives) {
            r.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(desc)
                    .withInsertHandler { ctx, _ ->
                        // nl-else / nl-pre / nl-once render without values.
                        val needsValue = name !in setOf("nl-else", "nl-pre", "nl-once")
                        if (needsValue) insertValueQuotes(ctx.editor, ctx.tailOffset)
                    },
            )
        }
        for ((name, desc) in prefixDirectives) {
            r.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(desc),
            )
        }
        for ((name, desc) in shorthand) {
            r.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(desc),
            )
        }
    }
}

/**
 * computePrefix walks back from the caret through characters that
 * make up a single attribute-name-being-typed: anything that isn't
 * whitespace, an angle bracket, a slash, an equals sign, or a quote.
 * This captures multi-character prefixes the default matcher would
 * truncate: "nl-", "nl-mod", ":sr", "@click.prev", "#hea".
 */
private fun computePrefix(parameters: CompletionParameters): String {
    val doc = parameters.editor.document.charsSequence
    val caret = parameters.offset
    var start = caret
    while (start > 0) {
        val c = doc[start - 1]
        if (c.isWhitespace() || c == '<' || c == '>' || c == '/' || c == '=' || c == '"' || c == '\'') break
        start--
    }
    return doc.subSequence(start, caret).toString()
}

/**
 * insertValueQuotes drops ="" past the inserted attribute name and
 * positions the caret between the quotes — same flow the platform's
 * own HTML attribute completion uses.
 */
private fun insertValueQuotes(editor: Editor, tailOffset: Int) {
    editor.document.insertString(tailOffset, "=\"\"")
    editor.caretModel.moveToOffset(tailOffset + 2)
}