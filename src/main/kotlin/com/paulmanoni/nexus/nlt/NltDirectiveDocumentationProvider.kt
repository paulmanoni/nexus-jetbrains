package com.paulmanoni.nexus.nlt

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute

/**
 * Renders hover/quick-doc panels for nl-* / : / @ / # attribute
 * directives inside .nlt files. The IDE calls into this provider
 * whenever the user hovers over an attribute name or invokes
 * Ctrl+Q on one; we resolve the attribute, look up its directive
 * card, and return the rendered HTML.
 *
 * Scoped to .nlt files via the in-method file-name check; in
 * plain HTML editing the provider returns null and falls through
 * to the platform's built-in HTML docs.
 */
class NltDirectiveDocumentationProvider : DocumentationProvider {

    override fun getQuickNavigateInfo(
        element: PsiElement?,
        originalElement: PsiElement?,
    ): String? {
        val attr = findAttr(element, originalElement) ?: return null
        val card = directiveCard(attr.name) ?: return null
        return "${card.title} — ${card.short}"
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val attr = findAttr(element, originalElement) ?: return null
        val card = directiveCard(attr.name) ?: return null
        return card.html()
    }

    /**
     * The IDE's default doc-target resolution looks for symbols
     * (functions, classes). XML attributes aren't symbols, so we
     * teach the platform to treat the hovered attribute as the
     * documentation target. Without this, generateDoc never fires
     * on a bare attribute name token.
     */
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (!file.name.endsWith(".nlt")) return null
        val ctx = contextElement ?: return null
        val attr = PsiTreeUtil.getParentOfType(ctx, XmlAttribute::class.java) ?: return null
        if (directiveCard(attr.name) == null) return null
        return attr
    }
}

private fun findAttr(element: PsiElement?, originalElement: PsiElement?): XmlAttribute? {
    val e = element ?: originalElement ?: return null
    val file = e.containingFile ?: return null
    if (!file.name.endsWith(".nlt")) return null
    return e as? XmlAttribute
        ?: PsiTreeUtil.getParentOfType(e, XmlAttribute::class.java)
}

/**
 * Card is the doc payload for one directive: a short tag-line
 * (shown in quick-info popups), a longer narrative paragraph,
 * and an inline example. Rendered to the platform's expected
 * subset of HTML in html().
 */
private data class Card(
    val title: String,
    val short: String,
    val long: String,
    val example: String,
) {
    fun html(): String = buildString {
        append("<p><b>").append(escape(title)).append("</b> — ").append(escape(short)).append("</p>")
        append("<p>").append(escape(long).replace("\n", "<br>")).append("</p>")
        if (example.isNotEmpty()) {
            append("<pre><code>").append(escape(example)).append("</code></pre>")
        }
    }
}

private fun escape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * directiveCard maps an attribute name to its hover card, or
 * null for attributes the platform should keep documenting on
 * its own. Strips event modifiers (.prevent, .stop, .enter) so
 * @click.prevent surfaces the @click card.
 */
private fun directiveCard(rawName: String): Card? {
    val name = rawName.substringBefore('.')
    return when {
        name == "nl-if" -> Card(
            title = "nl-if",
            short = "conditional rendering",
            long = "Renders the element only when the expression is truthy. " +
                "Adjacent siblings can chain with nl-else-if and nl-else.",
            example = """<p nl-if="len(Posts) == 0">No posts match.</p>"""
        )
        name == "nl-else-if" -> Card(
            title = "nl-else-if",
            short = "secondary branch",
            long = "Renders when the preceding sibling's nl-if (or nl-else-if) was false " +
                "AND this expression is truthy. Must be an immediate sibling of nl-if.",
            example = """<p nl-if="...">A</p>
<p nl-else-if="...">B</p>
<p nl-else>C</p>"""
        )
        name == "nl-else" -> Card(
            title = "nl-else",
            short = "fallback branch",
            long = "Renders when the preceding nl-if / nl-else-if siblings were all false. " +
                "No expression — must be an immediate sibling.",
            example = """<p nl-if="cond">yes</p>
<p nl-else>no</p>"""
        )
        name == "nl-for" -> Card(
            title = "nl-for",
            short = "list rendering",
            long = "Renders one element per item in an iterable. Syntax \"x in Xs\". " +
                "The iteration var becomes a scope binding visible to inner expressions. " +
                "Pair with :key=\"item.ID\" so the diff can preserve DOM identity on reorder.",
            example = """<PostRow nl-for="p in Posts" :key="p.ID" :post="p"/>"""
        )
        name == "nl-show" -> Card(
            title = "nl-show",
            short = "conditional visibility (planned)",
            long = "Toggles display via CSS without removing the element from the tree. " +
                "Parsed today; runtime behavior lands in a follow-up.",
            example = """<div nl-show="active">…</div>"""
        )
        name == "nl-model" -> Card(
            title = "nl-model",
            short = "two-way input binding",
            long = "Binds an input's value to a component field. Edits push __model events " +
                "that the engine writes back via reflection. Supports value-coercion modifiers " +
                "(.lazy = update on change instead of input; .trim = strings.TrimSpace; " +
                ".number = parse to float64).",
            example = """<input nl-model="Filter" placeholder="Search…"/>"""
        )
        name == "nl-html" -> Card(
            title = "nl-html",
            short = "raw HTML output (planned)",
            long = "Renders the expression value as raw HTML, bypassing the default escape. " +
                "Use only with trusted content — XSS risk on user input. Parsed today; " +
                "runtime support is a follow-up.",
            example = """<div nl-html="MarkdownToHTML(body)"></div>"""
        )
        name == "nl-text" -> Card(
            title = "nl-text",
            short = "text node binding (planned)",
            long = "Sets the element's text content from the expression. Equivalent to " +
                "{{ expr }} as the sole child but avoids whitespace pitfalls.",
            example = """<span nl-text="username"></span>"""
        )
        name == "nl-once" -> Card(
            title = "nl-once",
            short = "render-time hint (no-op v1)",
            long = "Hints that this subtree never changes, so the diff algorithm can skip it. " +
                "Accepted by the parser; treated as a no-op in v1.",
            example = """<aside nl-once>{{ staticHeader }}</aside>"""
        )
        name == "nl-pre" -> Card(
            title = "nl-pre",
            short = "skip interpolation (planned)",
            long = "Disables {{ }} parsing inside the subtree so the literal braces render " +
                "as text. Useful for embedding documentation about the engine itself.",
            example = """<pre nl-pre>use {{ Posts }} like this</pre>"""
        )
        name == "nl-slot" -> Card(
            title = "nl-slot",
            short = "slot definition (planned)",
            long = "Defines a slot in a component's template; parents fill the slot via " +
                "nl-slot:name=\"…\" on child content.",
            example = """<slot nl-slot="header"></slot>"""
        )
        name == "nl-hook" -> Card(
            title = "nl-hook",
            short = "JS hook (escape hatch for widgets)",
            long = "Names a hook in window.NLHooks. After each render the client fires " +
                "the hook's mounted(el) / updated(el) / destroyed(el) callbacks against " +
                "the marked element. Use it to bootstrap third-party widgets — date " +
                "pickers, charts, maps — that need direct DOM access.",
            example = """<input nl-hook="DatePicker"/>"""
        )
        name == "nl-stream" -> Card(
            title = "nl-stream",
            short = "incremental list container",
            long = "Marks a container whose children are mutated by ctx.Stream(\"<name>\").Append " +
                "(also Prepend, Delete, Update, Reset). Each call ships a single stream-op " +
                "frame; the surrounding template never re-renders for these updates. Right " +
                "for chat feeds, activity logs, large append-only lists.\n" +
                "Children must carry id=\"…\" for delete/update lookups.",
            example = """<ul nl-stream="messages"></ul>"""
        )
        name == "nl-navigate" -> Card(
            title = "nl-navigate",
            short = "in-WS page transition",
            long = "Marker attribute on an <a> tag. Clicks send a \"navigate\" message over " +
                "the existing WebSocket instead of doing a full page reload; the server " +
                "swaps the component, the client updates the address bar via " +
                "history.pushState. Modifier-key clicks (Cmd/Ctrl/Shift) and external " +
                "hrefs fall through to default browser navigation.",
            example = """<a nl-navigate href="/about">About</a>"""
        )

        // Prefix directives
        name.startsWith("nl-bind:") || rawName.startsWith(":") -> Card(
            title = if (rawName.startsWith(":")) ":attr (nl-bind: shorthand)" else "nl-bind:attr",
            short = "attribute binding",
            long = "Binds an attribute to an expression evaluated against the component's " +
                "scope. Re-evaluates on every render; the diff ships only the new value " +
                "when it changes.",
            example = """<a :href="\"/posts/\" + post.Slug">{{ post.Title }}</a>"""
        )
        name.startsWith("nl-on:") || rawName.startsWith("@") -> Card(
            title = if (rawName.startsWith("@")) "@event (nl-on: shorthand)" else "nl-on:event",
            short = "event handler",
            long = "Sends an event to the component over the WS. The handler is a method " +
                "on the component struct (TitleCased event name). Modifiers: " +
                ".prevent / .stop / .once for event control; .enter / .escape / .ctrl / " +
                "etc. for keyboard filters; .window or .document to attach at the global " +
                "target instead of via mount delegation.",
            example = """<button @click="like" :data-id="post.ID">♥</button>"""
        )
        name.startsWith("nl-slot:") || rawName.startsWith("#") -> Card(
            title = if (rawName.startsWith("#")) "#slot (nl-slot: shorthand)" else "nl-slot:name",
            short = "slot routing (planned)",
            long = "Routes a child fragment into a named slot on the parent component. " +
                "Parsed today; runtime behavior is a follow-up.",
            example = """<header #header>Page title</header>"""
        )

        else -> null
    }
}