package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.paulmanoni.nexus.nlt.expr.ExpressionContext
import com.paulmanoni.nexus.nlt.expr.GoScanner
import com.paulmanoni.nexus.nlt.expr.Scope
import com.paulmanoni.nexus.nlt.expr.componentNameFor
import com.paulmanoni.nexus.nlt.expr.detectExpressionContext
import com.paulmanoni.nexus.nlt.expr.resolveExpression

/**
 * Cmd+B / Cmd+Click on an identifier inside a .nlt expression
 * (interpolation, bound attribute, nl-for RHS, or event handler
 * value) jumps to the field or method in Go source that declares
 * the symbol.
 *
 * Resolution mirrors the completion contributor: build the scope
 * from the component struct + enclosing nl-for bindings, then
 * either look up the bare ident in scope (top-level field) or
 * walk the chain prefix to a type and locate the terminal
 * field/method on that type.
 *
 * Returns NavigatablePsiElement-bearing PsiFile references with
 * an offset, so the platform opens the .go file and positions
 * the caret on the declaration.
 *
 * Limitations:
 *   - Loop-variable navigation jumps to the field on the
 *     iterable's element type, not the nl-for declaration in
 *     the .nlt file. That's usually what you want (you're asking
 *     "where is this thing defined" — the struct field is the
 *     real source of truth).
 *   - Function calls (len, etc.) aren't resolved — they're built
 *     into the runtime, not a Go symbol the user can navigate to.
 */
class NltExpressionGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val project = sourceElement.project
        val ilm = InjectedLanguageManager.getInstance(project)

        // Injection-aware: when caret is inside the NexusExpr
        // injection, walk back to the host XmlAttributeValue PSI.
        // Use getTopLevelFile + injectedToHost (reliable on copies)
        // rather than getInjectionHost (which can return null on
        // editor copies in some IDE versions).
        val positionFile = sourceElement.containingFile ?: return null
        val topFile: PsiFile = ilm.getTopLevelFile(positionFile) ?: positionFile
        val (file, position, hostOffset) = if (topFile !== positionFile) {
            val ho = ilm.injectedToHost(positionFile, offset)
            val hostElem = topFile.findElementAt(ho) ?: return null
            Triple(topFile, hostElem, ho)
        } else {
            Triple(positionFile, sourceElement, offset)
        }

        if (!file.name.endsWith(".nlt")) return null

        val ctx = detectExpressionContext(position, hostOffset) ?: return null
        val scanner = GoScanner(project)
        val scope = Scope.build(position, scanner)

        // Event handler navigation: value is a single method name.
        // Events bubble from child components to whoever hosts them,
        // so the handler frequently lives on a PARENT component
        // rather than the local one (the child's same-named method,
        // if it exists at all, is typically an empty stub). Walk
        // every registered component's methods, filtered by name +
        // event-handler signature. Local component first so direct
        // handlers resolve without an unnecessary picker.
        if (ctx.kind == ExpressionContext.Kind.EventHandler) {
            // The value may be a bare ident ("like") or a call
            // expression ("like(Post.ID, msg)"); we want to land
            // on the handler method either way. Strip everything
            // from the first '(' onward, then trim.
            val raw = ctx.text.trim()
            val methodName = raw.substringBefore('(').trim()
            if (methodName.isEmpty()) return null
            val pm = PsiManager.getInstance(project)
            val localName = componentNameFor(file, scanner)
            val localStructName = localName?.let(scanner::structFor)?.name
            val matches = mutableListOf<GoScanner.GoMethod>()

            // Local first.
            if (localStructName != null) {
                for (m in scanner.methodsOf(localStructName)) {
                    if (matchesHandler(m, methodName)) matches += m
                }
            }
            // Then every other registered component — handler may
            // live on a parent that hosts <ThisComponent /> and
            // catches the bubbled event.
            for ((_, structName) in scanner.catalog().components) {
                if (structName == localStructName) continue
                for (m in scanner.methodsOf(structName)) {
                    if (matchesHandler(m, methodName)) matches += m
                }
            }
            if (matches.isEmpty()) return null

            // Empty-stub deprioritization: if any candidate has a
            // real body, drop the empty stubs. This makes Cmd+B from
            // a child component's @event handler land directly on
            // the parent's bubbled handler when the child only has
            // a placeholder. If every candidate is empty, return
            // them all — the stub is still a meaningful target.
            val chosen = matches.filter { !it.isEmpty }.ifEmpty { matches }
            return chosen.map { GoSymbolElement(pm, it.file, it.offset) }.toTypedArray()
        }

        // Expression contexts: find the ident at the caret and its
        // chain prefix.
        val (chain, identAtCaret) = chainAtCaret(ctx.text, ctx.caretInText) ?: return null
        if (identAtCaret.isEmpty()) return null

        val prefix = chain.dropLast(1)
        if (prefix.isEmpty()) {
            // Top-level ident: look up in scope. If it's a component
            // field, locate it on the struct and navigate.
            val componentName = componentNameFor(file, scanner) ?: return null
            val struct = scanner.structFor(componentName) ?: return null
            val field = struct.fields.firstOrNull { it.name == identAtCaret } ?: return null
            return arrayOf(GoSymbolElement(PsiManager.getInstance(project), field.file, field.offset))
        }

        // Chained: resolve the prefix to a type, then find the
        // terminal ident as a field on that struct.
        val prefixExpr = prefix.joinToString(".")
        val ownerType = resolveExpression(prefixExpr, scope, scanner) ?: return null
        val ownerStruct = scanner.structOfType(ownerType) ?: return null
        val field = ownerStruct.fields.firstOrNull { it.name == identAtCaret } ?: return null
        return arrayOf(GoSymbolElement(PsiManager.getInstance(project), field.file, field.offset))
    }
}

/**
 * matchesHandler returns true if a Go method is a viable event-
 * handler navigation target: name matches (case-insensitive
 * because templates often use lowercase "like" for Go's "Like"),
 * and the method isn't a known lifecycle hook (Mount / Refresh /
 * Unmount) that the engine calls automatically rather than in
 * response to an event.
 *
 * Both handler signatures qualify: the legacy (ctx *Ctx, p Payload)
 * form and the typed (ctx *Ctx, id int, body string) form from the
 * call-style @click="like(Post.ID)" syntax. The Payload-only
 * filter the prior version used would have hidden the typed form
 * from Cmd+B.
 */
private val LIFECYCLE_METHODS = setOf("Mount", "Refresh", "Unmount")

private fun matchesHandler(m: GoScanner.GoMethod, methodName: String): Boolean {
    if (!m.name.equals(methodName, ignoreCase = true)) return false
    if (LIFECYCLE_METHODS.contains(m.name)) return false
    return true
}

/**
 * chainAtCaret extracts the dotted-identifier chain that contains
 * the caret and returns (segments, identAtCaret). Segments are
 * ordered as written; identAtCaret is the last segment iff the
 * caret sits on it (otherwise it's the segment the caret is in).
 *
 * Examples (^ = caret):
 *   "Post.ID"        ^ on D      → ([Post, ID], "ID")
 *   "Post.ID"        ^ on s      → ([Post, ID], "Post")
 *   "len(Posts)"     ^ on s      → ([Posts], "Posts")
 *   "p.title"        ^ on .      → ([p], "p")   // caret in punct, snap left
 */
private fun chainAtCaret(text: String, caret: Int): Pair<List<String>, String>? {
    if (text.isEmpty()) return null
    val safeCaret = caret.coerceIn(0, text.length)

    // Find the ident token containing or immediately to the left of
    // the caret. Walk left to find token start, then right to find
    // token end. Dots break tokens but stay attached as separators
    // when walking the chain.
    val isIdent: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }

    var lo = safeCaret
    while (lo > 0 && isIdent(text[lo - 1])) lo--
    var hi = safeCaret
    while (hi < text.length && isIdent(text[hi])) hi++
    if (lo == hi) {
        // Caret not on an ident — try one position left (e.g. caret
        // sitting just before a closing paren).
        if (safeCaret > 0 && isIdent(text[safeCaret - 1])) {
            lo = safeCaret - 1
            while (lo > 0 && isIdent(text[lo - 1])) lo--
            hi = safeCaret
        } else {
            return null
        }
    }
    val ident = text.substring(lo, hi)

    // Walk left past dot-separated idents to assemble the chain.
    val segments = mutableListOf(ident)
    var i = lo
    while (i >= 2 && text[i - 1] == '.') {
        var j = i - 1
        while (j > 0 && isIdent(text[j - 1])) j--
        if (j == i - 1) break // empty segment, malformed
        segments += text.substring(j, i - 1)
        i = j
    }
    segments.reverse()
    return segments to ident
}

/**
 * GoSymbolElement is a lightweight navigation target. The platform
 * goto pipeline calls navigate() on it; we open the file and move
 * the caret to the captured offset.
 *
 * We don't construct a real Go PSI element because the IDE may or
 * may not have a Go parser available (CE doesn't), and binding to
 * the Go plugin's API would couple our build to a specific Go
 * plugin version per IDE release.
 */
private class GoSymbolElement(
    psiManager: PsiManager,
    private val file: VirtualFile,
    private val offset: Int,
) : com.intellij.psi.impl.FakePsiElement() {

    private val pm = psiManager

    override fun getParent(): PsiElement? = pm.findFile(file)
    override fun getContainingFile(): PsiFile? = pm.findFile(file)
    override fun getName(): String = file.name

    override fun navigate(requestFocus: Boolean) {
        val psiFile = pm.findFile(file) ?: return
        com.intellij.openapi.fileEditor.OpenFileDescriptor(
            psiFile.project, file, offset,
        ).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
}
