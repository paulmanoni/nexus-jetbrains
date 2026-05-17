package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.PlatformIcons
import com.intellij.util.ProcessingContext
import com.paulmanoni.nexus.nlt.expr.ExpressionContext
import com.paulmanoni.nexus.nlt.expr.GoScanner
import com.paulmanoni.nexus.nlt.expr.Scope
import com.paulmanoni.nexus.nlt.expr.componentNameFor
import com.paulmanoni.nexus.nlt.expr.detectExpressionContext
import com.paulmanoni.nexus.nlt.expr.resolveChainForCompletion
import com.paulmanoni.nexus.nlt.expr.typedSuffix

private val LOG = Logger.getInstance("nexus.nlt.completion")

/**
 * Type-aware completion for expressions inside .nlt files.
 *
 * Activates inside:
 *   - {{ … }} text interpolation
 *   - :foo="…", nl-bind:foo="…"
 *   - nl-if="…", nl-else-if="…", nl-show="…", nl-model="…",
 *     nl-html="…", nl-text="…"
 *   - the RHS of nl-for="x in Xs"
 *
 * Resolution chain:
 *
 *   1. detectExpressionContext finds the expression text + caret
 *      position within it, given the PSI element under the caret.
 *   2. Scope.build walks PSI ancestors to build the name → Go-
 *      type-ref map: component fields + every enclosing nl-for
 *      binding.
 *   3. If the typed segment contains a `.`, resolve the chain
 *      before the last dot to a Go type and offer that type's
 *      exported fields. Otherwise offer the full scope's
 *      top-level names.
 *
 * Every leaf hit goes through a PlainPrefixMatcher reset to the
 * partial identifier the user is typing — IntelliJ's default
 * matcher splits on dots and would corrupt our prefix.
 *
 * Scope and resolver are best-effort. When the GoScanner can't
 * find the component, can't parse a struct, or can't unwrap a
 * type, we silently return — the other (directive) completion
 * contributor still fires, and the user just doesn't get the
 * type-aware list.
 */
class NltExpressionCompletionContributor : CompletionContributor() {

    init {
        // We need this to fire EVERYWHERE the caret might land in a
        // .nlt file — inside text content (for {{ }}) and inside
        // attribute values. Filter by file extension inside.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            ExpressionCompletionProvider(),
        )
    }
}

private class ExpressionCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val project = parameters.position.project

        // Fundamental sidestep of all injection-copy headaches:
        // go through the EDITOR to find the host .nlt file. The
        // editor's document is the host document regardless of
        // whether the caret sits in a NexusExpr-injected
        // fragment. The caret offset on the editor is the offset
        // in the host document, so PSI lookups land on the host
        // XmlAttributeValue without any injectedToHost dance.
        //
        // Fallback chain: editor → original-file PSI translation
        // via InjectedLanguageManager. Both produce a host .nlt
        // file + a caret offset within it.
        val resolved = resolveHost(parameters, project) ?: run {
            LOG.warn("nl-completion: could not resolve host .nlt file (position file: ${parameters.position.containingFile.name}, original: ${parameters.originalFile.name})")
            return
        }
        if (!resolved.file.name.endsWith(".nlt")) {
            LOG.warn("nl-completion: resolved file is not .nlt: ${resolved.file.name}")
            return
        }
        LOG.warn("nl-completion: firing on ${resolved.file.name} at offset ${resolved.offset}")

        val ctx = detectExpressionContext(resolved.position, resolved.offset)
        if (ctx == null) {
            LOG.warn("nl-completion: detectExpressionContext returned null")
            return
        }
        LOG.warn("nl-completion: ctx kind=${ctx.kind} text='${ctx.text}' caretInText=${ctx.caretInText}")

        val scanner = GoScanner(project)
        val scope = Scope.build(resolved.position, scanner)

        // EventHandler kind: caret is in @ev="…" / nl-on:ev="…". The
        // value is a single Go method name, not an expression. Offer
        // methods on the local component struct whose signature
        // matches an event handler (takes template.Payload). Skip
        // lifecycle methods (Mount / Refresh / Unmount).
        if (ctx.kind == ExpressionContext.Kind.EventHandler) {
            val componentName = componentNameFor(resolved.file, scanner) ?: return
            val struct = scanner.structFor(componentName) ?: return
            val r = result.withPrefixMatcher(PlainPrefixMatcher(ctx.typed))
            for (m in scanner.methodsOf(struct.name)) {
                if (!isEventHandlerSignature(m.name, m.params)) continue
                r.addElement(
                    LookupElementBuilder.create(m.name)
                        .withTypeText("handler")
                        .withIcon(PlatformIcons.METHOD_ICON),
                )
            }
            return
        }

        val typed = ctx.typed
        val suffix = typedSuffix(typed)

        // The platform's default prefix is whatever it inferred from
        // its lexer — usually too short (stops at the dot). Reset to
        // the partial-identifier suffix the user is mid-typing so
        // our LookupElements match correctly.
        val r = result.withPrefixMatcher(PlainPrefixMatcher(suffix))

        val ownerType = resolveChainForCompletion(typed, scope, scanner)
        if (ownerType != null) {
            // Caret is after a dot; offer fields of the owning type.
            val struct = scanner.structOfType(ownerType) ?: return
            for (f in struct.fields) {
                r.addElement(
                    LookupElementBuilder.create(f.name)
                        .withTypeText(f.typeRef)
                        .withIcon(PlatformIcons.FIELD_ICON),
                )
            }
            return
        }

        // No leading dot — offer everything in scope (component
        // fields + nl-for vars). Skip when context is LoopIter:
        // that position wants an iterable, and the scope's strings
        // aren't filtered by type today.
        if (ctx.kind == ExpressionContext.Kind.LoopIter) {
            // Even for loop iter we can usefully offer all scope
            // names — the user picks the slice they meant.
        }
        for ((name, typeRef) in scope.all()) {
            r.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(typeRef)
                    .withIcon(PlatformIcons.FIELD_ICON),
            )
        }
    }
}

private data class Resolved(
    val file: PsiFile,
    val position: PsiElement,
    val offset: Int,
)

/**
 * resolveHost finds the host .nlt PsiFile + the caret offset
 * within it, regardless of whether the caret is in plain HTML
 * (text interpolation, plain attribute) or inside a NexusExpr
 * injection. Prefers the editor route since it sidesteps every
 * injection-copy edge case; falls back through the original
 * file when no editor is available (programmatic completion).
 */
private fun resolveHost(
    parameters: CompletionParameters,
    project: com.intellij.openapi.project.Project,
): Resolved? {
    // Editor route: the editor's document IS the host (.nlt)
    // document. The editor's caret offset is the host offset.
    val editor = parameters.editor
    if (editor != null) {
        val doc = editor.document
        val pm = PsiDocumentManager.getInstance(project)
        val hostFile = pm.getPsiFile(doc)
        if (hostFile != null && hostFile.name.endsWith(".nlt")) {
            val off = editor.caretModel.offset
            val elem = hostFile.findElementAt(off)
                ?: hostFile.findElementAt((off - 1).coerceAtLeast(0))
                ?: return Resolved(hostFile, hostFile, off)
            return Resolved(hostFile, elem, off)
        }
    }
    // Fallback: walk via originalFile + InjectedLanguageManager.
    val originalFile = parameters.originalFile
    if (originalFile.name.endsWith(".nlt")) {
        return Resolved(originalFile, parameters.position, parameters.offset)
    }
    val ilm = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
    val top = ilm.getTopLevelFile(originalFile) ?: return null
    if (!top.name.endsWith(".nlt")) return null
    val hostOff = ilm.injectedToHost(originalFile, parameters.offset)
    val elem = top.findElementAt(hostOff) ?: return null
    return Resolved(top, elem, hostOff)
}

/**
 * isEventHandlerSignature filters methods to keep only the ones
 * that could be invoked by an @event="<name>" template binding.
 * Heuristic: anything except the framework's named lifecycle
 * hooks (Mount / Refresh / Unmount). Both handler shapes pass:
 *   - (ctx *Ctx, p Payload)        legacy
 *   - (ctx *Ctx, id int, body str) typed (call-form
 *                                   @click="like(Post.ID, msg)")
 *
 * The prior Payload-substring check failed for the typed form;
 * relying on name lifecycle filtering is more permissive and
 * matches the engine's actual dispatch.
 */
private val LIFECYCLE_METHOD_NAMES = setOf("Mount", "Refresh", "Unmount")

private fun isEventHandlerSignature(name: String, @Suppress("UNUSED_PARAMETER") params: String): Boolean =
    !LIFECYCLE_METHOD_NAMES.contains(name)