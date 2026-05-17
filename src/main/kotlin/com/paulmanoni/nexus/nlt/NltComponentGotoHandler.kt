package com.paulmanoni.nexus.nlt

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Cmd+B / Cmd+Click on a `<UserCard />`-style component tag inside
 * a .nlt file jumps to the .nlt file that defines that component.
 *
 * Resolution is by filename: the convention `engine.Register("Foo",
 * fooTemplate, …)` pairs a component name with the .nlt file the
 * `//go:embed` line in Go pulls in, and apps overwhelmingly name
 * the file after the component (PostRow.nlt / UserCard.nlt). So
 * "tag name + .nlt" finds the right file in the vast majority of
 * cases, with no registry plumbing needed on the IDE side.
 *
 * Limitations:
 *   - Components whose .nlt filename diverges from the registered
 *     name (e.g. snake_case files mapped to PascalCase names) won't
 *     resolve. A future enhancement would parse the Go registration
 *     calls; for v1 the file-naming convention is enough.
 *   - Only handles single-file lookup. Multiple .nlt files with the
 *     same name across different directories all become candidates;
 *     IntelliJ shows the picker.
 */
class NltComponentGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val file = sourceElement.containingFile ?: return null
        if (!file.name.endsWith(".nlt")) return null

        // Only navigate when the cursor is sitting on a tag's name
        // token (not on an attribute, value, text content, etc.).
        val tag = PsiTreeUtil.getParentOfType(sourceElement, XmlTag::class.java) ?: return null
        val tagName = tag.name
        if (!isComponentTagName(tagName)) return null
        // sourceElement is typically a leaf XML_NAME token; the
        // parent tag's name should contain it. The strict equality
        // check avoids firing when the cursor is over attribute
        // text that happens to be inside the same tag.
        val tokenText = sourceElement.text ?: return null
        if (!tagName.contains(tokenText) && tokenText != tagName) return null

        val project = file.project
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)

        // Try common filename conventions because apps style their
        // .nlt files differently than their PascalCase component
        // names. Search each variant; the first that yields hits
        // wins. PostRow → PostRow.nlt / postRow.nlt / post_row.nlt
        // / post-row.nlt / postrow.nlt.
        for (variant in filenameVariantsFor(tagName)) {
            val hits = FilenameIndex.getVirtualFilesByName(variant, scope)
            if (hits.isEmpty()) continue
            return hits.mapNotNull(psiManager::findFile).toTypedArray()
        }
        return null
    }
}

private fun filenameVariantsFor(tagName: String): List<String> {
    val lower = tagName.lowercase()
    val camel = tagName.replaceFirstChar { it.lowercaseChar() }
    val snake = toSeparated(tagName, '_')
    val kebab = toSeparated(tagName, '-')
    return listOf(tagName, camel, snake, kebab, lower)
        .distinct()
        .map { "$it.nlt" }
}

/**
 * toSeparated turns a PascalCase or camelCase name into a
 * separator-joined lowercase form: PostRow → post_row, MyURL →
 * my_url, alreadyLower → already_lower. Acronym runs collapse to
 * the natural reading (HTTPRouter → http_router); the heuristic
 * sometimes splits surprisingly but matches what most code
 * generators do.
 */
private fun toSeparated(name: String, sep: Char): String {
    if (name.isEmpty()) return name
    val sb = StringBuilder()
    for ((i, c) in name.withIndex()) {
        if (c.isUpperCase() && i > 0) {
            val prev = name[i - 1]
            val next = if (i + 1 < name.length) name[i + 1] else null
            // Insert a separator between either:
            //   - a lowercase or digit followed by an uppercase (PostRow → Post_Row)
            //   - an acronym tail followed by a normal capitalized word (HTMLEditor → HTML_Editor)
            if (prev.isLowerCase() || prev.isDigit() ||
                (next != null && next.isLowerCase())
            ) {
                sb.append(sep)
            }
        }
        sb.append(c.lowercaseChar())
    }
    return sb.toString()
}

/**
 * Component tag names follow the Vue convention: PascalCase. The
 * "template" element is excluded so the <template nl-if=…> slot/
 * group wrapper doesn't trigger navigation.
 */
private fun isComponentTagName(tag: String): Boolean {
    if (tag.isEmpty()) return false
    if (tag.equals("template", ignoreCase = true)) return false
    val c = tag[0]
    return c in 'A'..'Z'
}