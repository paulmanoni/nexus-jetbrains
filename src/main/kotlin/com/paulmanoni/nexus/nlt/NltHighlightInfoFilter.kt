package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiFile

/**
 * Safety-net filter for HTML warnings on .nlt files that the
 * standard InspectionSuppressor can't catch — typically because
 * they're emitted by Annotators (not Inspections) and don't have
 * a tool ID an InspectionSuppressor can match against.
 *
 * Matching is by the warning's description text. The patterns
 * cover the legitimate-SFC constructs an HTML-shaped editor will
 * flag:
 *
 *   - "Empty tag doesn't work in some browsers" — fires on
 *     <PostRow />-style self-closed component tags.
 *   - "not allowed here" — generic "this attribute / tag isn't
 *     valid in this context" message families.
 *   - "Unknown HTML tag" — PascalCase component refs (also
 *     covered by the suppressor; double-cover is cheap).
 *
 * Scope: only .nlt files. Non-.nlt highlights pass through
 * untouched — the platform's HTML inspector still complains about
 * malformed .html files in the same project.
 */
class NltHighlightInfoFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file?.name?.endsWith(".nlt") != true) return true
        val desc = highlightInfo.description ?: return true
        for (pattern in BLOCKED_PHRASES) {
            if (desc.contains(pattern, ignoreCase = true)) return false
        }
        return true
    }
}

private val BLOCKED_PHRASES = listOf(
    "doesn't work in some browsers",
    "not allowed here",
    "Unknown HTML tag",
    "Empty tag",
)