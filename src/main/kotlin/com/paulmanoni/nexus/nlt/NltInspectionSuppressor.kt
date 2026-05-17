package com.paulmanoni.nexus.nlt

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

/**
 * Silences the bundled HTML inspections that fire on legitimate
 * .nlt constructs:
 *
 *   - HtmlUnknownTag: <PostRow />, <UserCard /> — PascalCase
 *     component references that the platform's HTML parser doesn't
 *     have a descriptor for.
 *   - HtmlUnknownAttribute: catches anything not whitelisted by our
 *     XmlAttributeDescriptorsProvider — most notably <style scoped>,
 *     which Vue/SFCs still use even though HTML5 removed it.
 *   - CheckEmptyScriptTag / HtmlSelfClosingTag: fire on
 *     <PostRow />-style empty tags because HTML's self-close
 *     syntax is reserved for void elements (<br/>, <img/>).
 *   - HtmlNonCompliantBrowserFeature: a catch-all the platform uses
 *     for surface that varies by browser; some IDE versions surface
 *     SFC artifacts through it.
 *
 * Each entry below corresponds to a specific inspection short-name
 * IntelliJ exposes; the IDs aren't formally documented but are
 * stable across releases. Adding an inspection ID here disables
 * the inspection ENTIRELY on .nlt files — there is no
 * fine-grained "only suppress on this element" because the SFC
 * model legitimizes the construct globally, not per-element.
 *
 * Scope-limited to .nlt; regular .html / .xml files are untouched.
 */
class NltInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val file = element.containingFile ?: return false
        if (!file.name.endsWith(".nlt")) return false
        return toolId in SUPPRESSED_INSPECTIONS
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        emptyArray()
}

private val SUPPRESSED_INSPECTIONS = setOf(
    "HtmlUnknownTag",
    "HtmlUnknownAttribute",
    "CheckEmptyScriptTag",
    "HtmlSelfClosingTag",
    "HtmlNonCompliantBrowserFeature",
    // Some IDE builds expose the "Attribute X is not allowed here"
    // warning under either of these IDs; covered for safety.
    "HtmlUnknownAnchorTarget",
    "HtmlRequiredTitleElement",
)