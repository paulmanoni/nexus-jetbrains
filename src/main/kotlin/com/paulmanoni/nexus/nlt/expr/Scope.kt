package com.paulmanoni.nexus.nlt.expr

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Scope is the name → Go-type-ref map an expression resolves
 * against. Built by walking PSI ancestors from the caret:
 *
 *   - root binding = the component struct paired with the file
 *     (looked up by filename in the project's GoScanner catalog).
 *     Each exported field of that struct enters the scope as a
 *     top-level name with its declared type.
 *   - each nl-for ancestor introduces ONE additional binding:
 *     the iteration var, with type = elem(typeOf(iter)). Inner
 *     loops shadow outer.
 *
 * Type refs are kept as the raw text strings GoScanner produced
 * ("string", "*Post", "[]User", "time.Time"). The resolver
 * unwraps them on the fly.
 */
class Scope private constructor(
    val bindings: Map<String, String>,
    val scanner: GoScanner,
) {
    /** Returns the type ref for `name`, or null if unbound. */
    fun lookup(name: String): String? = bindings[name]

    /** All bindings — used by completion to offer top-level names. */
    fun all(): Map<String, String> = bindings

    companion object {
        /**
         * build constructs a scope for `element` by:
         *
         *  1. Looking up the file's component name (filename's stem
         *     up to first '.') in the GoScanner catalog. If the
         *     filename doesn't match a registration, the resolver
         *     also tries capitalized + PascalCased variants
         *     (post_row.nlt → PostRow).
         *
         *  2. Walking up XmlTag ancestors. For each ancestor with
         *     an nl-for attribute, splits "x in Xs", resolves the
         *     RHS against the scope-built-so-far, unwraps the
         *     slice/array, and adds `x → elem`.
         *
         * Innermost ancestors win because we apply them last in
         * the same map.
         */
        fun build(element: PsiElement, scanner: GoScanner): Scope {
            val bindings = mutableMapOf<String, String>()

            // 1. Component-level scope.
            val file = element.containingFile
            val componentName = componentNameFor(file, scanner)
            val struct = componentName?.let(scanner::structFor)
            struct?.fields?.forEach { f ->
                bindings[f.name] = f.typeRef
            }

            // 2. nl-for bindings from outermost to innermost so
            // shadowing happens by overwriting in-order.
            val tags = mutableListOf<XmlTag>()
            var cur: PsiElement? = element
            while (cur != null) {
                if (cur is XmlTag) tags += cur
                cur = cur.parent
            }
            for (tag in tags.reversed()) {
                val forAttr = tag.getAttributeValue("nl-for") ?: continue
                val (v, iter) = parseForExpr(forAttr) ?: continue
                // Resolve iter against bindings built so far.
                val iterTypeRef = resolveExpression(iter, Scope(bindings.toMap(), scanner), scanner) ?: continue
                val elem = scanner.elemOfType(iterTypeRef) ?: continue
                bindings[v] = elem
            }
            return Scope(bindings.toMap(), scanner)
        }
    }
}

/**
 * componentNameFor maps a file to the component name registered
 * for it. Generates name candidates from the stem and returns the
 * first one that's actually present in the GoScanner catalog —
 * that's what gives us a real component → struct mapping rather
 * than a guess.
 *
 * Conventions tried, in order:
 *   - the stem as-written            (posts → posts)
 *   - capitalize the first character (posts → Posts)
 *   - PascalCase from snake / kebab  (post_row → PostRow, my-card → MyCard)
 *
 * Falls back to the first candidate (without registry confirmation)
 * if nothing matches — keeps scope building working in projects
 * where the scanner couldn't pick anything up.
 */
fun componentNameFor(file: PsiFile?, scanner: GoScanner): String? {
    val name = file?.name ?: return null
    if (!name.endsWith(".nlt")) return null
    val stem = name.removeSuffix(".nlt")

    val candidates = mutableListOf(
        stem,
        stem.replaceFirstChar(Char::titlecase),
        pascalCase(stem),
    ).distinct()

    val catalog = scanner.catalog()
    return candidates.firstOrNull { catalog.components.containsKey(it) }
        ?: candidates.firstOrNull()
}

private fun pascalCase(s: String): String {
    val sb = StringBuilder()
    var upNext = true
    for (c in s) {
        if (c == '_' || c == '-') {
            upNext = true
        } else {
            sb.append(if (upNext) c.titlecaseChar() else c)
            upNext = false
        }
    }
    return sb.toString()
}

/** parseForExpr decodes "x in xs" → ("x", "xs"). Returns null on malformed input. */
fun parseForExpr(expr: String): Pair<String, String>? {
    val parts = expr.split(" in ", limit = 2)
    if (parts.size != 2) return null
    val v = parts[0].trim()
    val iter = parts[1].trim()
    if (v.isEmpty() || iter.isEmpty() || v.contains(",")) return null
    return v to iter
}

/**
 * resolveExpression walks an expression chain "a.b.c" against the
 * scope and returns the resulting type ref, or null if any segment
 * fails to resolve.
 *
 * Only supports field-access chains — bare ident, dotted ident.
 * Method calls, indexing, function calls all return null (the
 * caller treats null as "no completion available"). v1 scope.
 */
fun resolveExpression(expr: String, scope: Scope, scanner: GoScanner): String? {
    val cleaned = expr.trim()
    if (cleaned.isEmpty()) return null
    // Reject anything that isn't a plain identifier chain.
    if (!IDENT_CHAIN.matches(cleaned)) return null

    val segments = cleaned.split(".")
    var typeRef = scope.lookup(segments.first()) ?: return null
    for (seg in segments.drop(1)) {
        val struct = scanner.structOfType(typeRef) ?: return null
        typeRef = scanner.fieldType(struct, seg) ?: return null
    }
    return typeRef
}

private val IDENT_CHAIN = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*""")

/**
 * resolveChainForCompletion is the dual of resolveExpression for
 * the COMPLETION case: the chain ends in a dot or a partial ident.
 * Returns the type that owns the suggestions: the type resolved up
 * to the segment BEFORE the trailing partial.
 *
 *   "" / "po"           → returns null + offer top-level scope names
 *   "post."             → returns type of post → offer its fields
 *   "post.au"           → returns type of post → offer its fields
 *   "post.author."      → returns type of post.author → offer fields
 *   "post.author.foo"   → returns type of post.author → offer fields
 *
 * The completion contributor distinguishes "no leading chain" by
 * checking whether the typed segment contains a dot.
 */
fun resolveChainForCompletion(typed: String, scope: Scope, scanner: GoScanner): String? {
    val cleaned = typed.trim()
    if (cleaned.isEmpty()) return null
    val dotIdx = cleaned.lastIndexOf('.')
    val prefix = if (dotIdx >= 0) cleaned.substring(0, dotIdx) else return null
    if (prefix.isEmpty()) return null
    return resolveExpression(prefix, scope, scanner)
}

/** Pulls the partial identifier the user is typing after the last dot. */
fun typedSuffix(typed: String): String {
    val dotIdx = typed.lastIndexOf('.')
    return if (dotIdx >= 0) typed.substring(dotIdx + 1) else typed
}