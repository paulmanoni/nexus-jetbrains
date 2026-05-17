package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.lang.injection.InjectedLanguageManager
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
        val ilm = InjectedLanguageManager.getInstance(project)

        // When the caret is inside the NexusExpr injection we inject
        // into expression attribute values, parameters.position lives
        // in the injected PSI and has no XmlAttributeValue ancestor —
        // detectAttribute would return null. Walk back to the host
        // PSI and translate the offset so the rest of the pipeline
        // sees the host XmlAttributeValue normally.
        val host = ilm.getInjectionHost(parameters.position)
        val resolved = if (host != null) {
            val hostFile: PsiFile = host.containingFile ?: return
            val injectedFile = parameters.position.containingFile
            val hostOffset = ilm.injectedToHost(injectedFile, parameters.offset)
            val hostElem: PsiElement = hostFile.findElementAt(hostOffset) ?: host
            Resolved(hostFile, hostElem, hostOffset)
        } else {
            Resolved(parameters.originalFile, parameters.position, parameters.offset)
        }

        if (!resolved.file.name.endsWith(".nlt")) return

        val ctx = detectExpressionContext(resolved.position, resolved.offset) ?: return

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