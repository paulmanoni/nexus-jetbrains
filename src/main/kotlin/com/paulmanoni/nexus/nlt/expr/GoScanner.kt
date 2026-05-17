package com.paulmanoni.nexus.nlt.expr

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.fileTypes.FileTypeManager

/**
 * GoScanner mines the project's .go files for two things the
 * .nlt completion contributor needs:
 *
 *   1. component registrations — `engine.Register("Name", src,
 *      func() Component { return &T{} })` and the equivalent
 *      `template.RegisterComponent` form. The name string + the
 *      returned struct type pin a .nlt file to a Go struct.
 *
 *   2. struct declarations — `type T struct { Field1 Type; … }`
 *      with their fields. Field types are kept as raw strings
 *      ("int", "[]Post", "*User", "string") and unwrapped on the
 *      fly during expression resolution.
 *
 * The scanner is intentionally regex-based — adding the Go plugin
 * as a compile-time dependency would couple the build to a single
 * Go-plugin-version per IDE release and gain us little, since our
 * type model is so narrow (no generics, no methods, no qualified
 * types). The common cases — plain struct, primitive fields,
 * pointer/slice fields to other local structs — all parse cleanly
 * with a small grammar.
 *
 * Limitations (documented; surface as "no completion" gracefully):
 *   - Generic types: parsed as the raw type ref, can't resolve.
 *   - Anonymous embedded fields: skipped.
 *   - Qualified types (time.Time, etc.): treated as opaque.
 *   - Factories that don't return &T{} inline: skipped.
 *   - File-level type aliases: not followed.
 */
class GoScanner(private val project: Project) {

    /**
     * Catalog is the full set of registrations + struct definitions
     * indexed across the project. Cached lazily per scan; callers
     * that want fresher data construct a new GoScanner.
     */
    data class Catalog(
        val components: Map<String, String>, // componentName → struct simple name
        val structs: Map<String, GoStruct>,  // struct simple name → fields
        val methods: Map<String, List<GoMethod>>, // struct simple name → methods
    )

    data class GoStruct(
        val name: String,
        val fields: List<GoField>,
        val file: VirtualFile,
    )

    data class GoField(
        val name: String,
        val typeRef: String, // raw text: "string", "*Post", "[]User", "time.Time"
        val file: VirtualFile,
        val offset: Int, // byte offset where the field name starts; for goto navigation
    )

    /**
     * GoMethod captures a method declared on a struct: its name,
     * the raw parameter list text (used to discriminate event
     * handlers vs lifecycle methods), and the file + offset where
     * the method name appears (for Cmd+B navigation).
     *
     * isEmpty is true when the method body contains only
     * whitespace between { and the matching } — a placeholder
     * stub the navigation handler can deprioritize when a richer
     * match exists on another component (the empty stub on a
     * child component vs the real bubbled handler on the parent).
     */
    data class GoMethod(
        val name: String,
        val params: String, // raw text between the parens: "ctx *template.Ctx, p template.Payload"
        val file: VirtualFile,
        val offset: Int,
        val isEmpty: Boolean,
    )

    private var cached: Catalog? = null

    fun catalog(): Catalog {
        cached?.let { return it }
        val built = scan()
        cached = built
        return built
    }

    private fun scan(): Catalog {
        val components = mutableMapOf<String, String>()
        val structs = mutableMapOf<String, GoStruct>()
        val methods = mutableMapOf<String, MutableList<GoMethod>>()

        val goType = FileTypeManager.getInstance().getFileTypeByExtension("go")
        val goFiles = FileTypeIndex.getFiles(goType, GlobalSearchScope.projectScope(project))

        for (vf in goFiles) {
            val content = try {
                String(vf.contentsToByteArray(), Charsets.UTF_8)
            } catch (_: Throwable) {
                continue
            }

            // Strip line comments to avoid matching inside them; block
            // comments left in for simplicity — they rarely contain
            // text that mimics our patterns at the top level.
            val cleaned = stripLineComments(content)

            for (m in REGISTER_RE.findAll(cleaned)) {
                val name = m.groupValues[1]
                val structName = m.groupValues[2]
                components[name] = structName
            }
            for (m in STRUCT_RE.findAll(cleaned)) {
                val name = m.groupValues[1]
                val body = m.groupValues[2]
                val bodyStart = m.range.first + m.value.indexOf('{') + 1
                structs[name] = GoStruct(name, parseFields(body, vf, bodyStart), vf)
            }
            for (m in METHOD_RE.findAll(cleaned)) {
                // Group 1: receiver type without leading * — that's
                // the struct simple name we key methods on.
                val recvType = m.groupValues[1]
                val methodName = m.groupValues[2]
                val params = m.groupValues[3]
                // Offset of the method name inside the captured match.
                val methodNameOffset = m.range.first + m.value.indexOf(methodName, startIndex = m.value.indexOf(')'))
                val isEmpty = isEmptyMethodBody(cleaned, m.range.last + 1)
                methods.getOrPut(recvType) { mutableListOf() } += GoMethod(
                    name = methodName,
                    params = params,
                    file = vf,
                    offset = methodNameOffset,
                    isEmpty = isEmpty,
                )
            }
        }
        return Catalog(components, structs, methods.mapValues { it.value.toList() })
    }

    fun structFor(componentName: String): GoStruct? {
        val cat = catalog()
        val structName = cat.components[componentName] ?: return null
        return cat.structs[structName]
    }

    /** Methods declared on the named struct (any signature). */
    fun methodsOf(structName: String): List<GoMethod> =
        catalog().methods[structName] ?: emptyList()

    /**
     * fieldType returns the type ref string of a field on a struct
     * (or one reached by walking through it). Used by the
     * expression resolver to chain Post.Author.Name lookups.
     */
    fun fieldType(struct: GoStruct, fieldName: String): String? {
        val direct = struct.fields.firstOrNull { it.name == fieldName }?.typeRef
        return direct
    }

    /**
     * structOfType maps a raw type ref ("*Post", "[]User", "Post")
     * to the struct it ultimately refers to, unwrapping pointers
     * and slices. Returns null for primitives, qualified types,
     * generics, or struct names we never saw at scan time.
     */
    fun structOfType(typeRef: String): GoStruct? {
        val name = unwrap(typeRef)
        return catalog().structs[name]
    }

    /** elemOfType("[]Post") = "Post"; for non-slice types returns null. */
    fun elemOfType(typeRef: String): String? {
        val t = typeRef.trim()
        return when {
            t.startsWith("[]") -> t.removePrefix("[]").trim()
            else -> null
        }
    }

    private fun unwrap(typeRef: String): String {
        var t = typeRef.trim()
        // Strip leading *, [], spaces. Doesn't fully canonicalize
        // multi-dimensional slices but those don't appear in real
        // nl-for sources.
        while (true) {
            val before = t
            if (t.startsWith("*")) t = t.removePrefix("*").trim()
            if (t.startsWith("[]")) t = t.removePrefix("[]").trim()
            if (t == before) break
        }
        // Qualified types (time.Time) — drop the package qualifier
        // because our struct index is by simple name.
        val dot = t.lastIndexOf('.')
        if (dot >= 0) t = t.substring(dot + 1)
        return t
    }

    private fun parseFields(body: String, vf: VirtualFile, bodyStart: Int): List<GoField> {
        val out = mutableListOf<GoField>()
        // Track our running offset through the body so we can record
        // where each field NAME begins in the source file — used by
        // the goto handler to land the caret on the field declaration
        // rather than just opening the file at line 1.
        var cursor = 0
        for (rawLine in body.lines()) {
            val lineStart = cursor
            cursor += rawLine.length + 1 // +1 for the newline consumed by lines()
            val trimmedFront = rawLine.trimStart()
            val leadingWs = rawLine.length - trimmedFront.length
            val line = trimmedFront.substringBefore("//").trim().substringBefore('`').trim()
            if (line.isEmpty()) continue
            val tokens = line.split(Regex("\\s+"), 2)
            if (tokens.size < 2) continue
            val namesPart = tokens[0]
            val typePart = tokens[1].trim()
            if (typePart.isEmpty()) continue
            val names = namesPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            // The first name starts at lineStart + leadingWs; later
            // names (multi-name fields) we approximate by searching
            // forward from that anchor.
            var searchFrom = leadingWs
            for (n in names) {
                if (!n.first().isUpperCase()) continue
                val rel = rawLine.indexOf(n, startIndex = searchFrom)
                val nameOffset = if (rel >= 0) bodyStart + lineStart + rel else bodyStart + lineStart + leadingWs
                if (rel >= 0) searchFrom = rel + n.length
                out += GoField(n, typePart, vf, nameOffset)
            }
        }
        return out
    }

    /**
     * isEmptyMethodBody peeks past the method signature to find
     * the body's opening `{`, then scans to the matching `}` with
     * depth counting, returning true if the body is whitespace
     * only. Used to deprioritize stub methods in handler-
     * navigation (a child component's empty Like vs the parent's
     * real bubbled handler).
     *
     * Brace counting skips strings and line/block comments so
     * literal `{` / `}` characters in those don't unbalance the
     * count. Quietly returns false on malformed input — we'd
     * rather show the stub than swallow a real method.
     */
    private fun isEmptyMethodBody(src: String, sigEnd: Int): Boolean {
        var i = sigEnd
        // Skip return type tokens between `)` and `{` (e.g. `error`,
        // `(int, error)`, `*Foo`).
        while (i < src.length && src[i] != '{') {
            if (src[i] == '\n') {
                // Conservative: a newline before { is fine in Go but
                // also a sign of malformed input. Keep going.
            }
            i++
        }
        if (i >= src.length) return false
        var depth = 1
        i++
        val start = i
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
                '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) i++
                        i++
                    }
                }
                '`' -> {
                    i++
                    while (i < src.length && src[i] != '`') i++
                }
                '/' -> {
                    if (i + 1 < src.length && src[i + 1] == '/') {
                        while (i < src.length && src[i] != '\n') i++
                        continue
                    }
                    if (i + 1 < src.length && src[i + 1] == '*') {
                        i += 2
                        while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        if (depth != 0) return false
        return src.substring(start, i).isBlank()
    }

    private fun stripLineComments(src: String): String {
        // Conservative: only strip // when preceded by whitespace or
        // start-of-line; this avoids damaging string literals that
        // happen to contain "//" (URLs, regex bodies). Imperfect but
        // good enough for scanning structure.
        val sb = StringBuilder(src.length)
        for (line in src.lines()) {
            val idx = line.indexOf("//")
            if (idx < 0) {
                sb.append(line).append('\n')
                continue
            }
            // Heuristic: if there's an unmatched quote before idx, the
            // // is inside a string — keep the line intact.
            val before = line.substring(0, idx)
            val dq = before.count { it == '"' }
            val bq = before.count { it == '`' }
            if (dq % 2 == 1 || bq % 2 == 1) {
                sb.append(line).append('\n')
            } else {
                sb.append(before).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        // Matches:
        //   engine.Register("Posts", postsTemplate, func() template.Component {
        //       return &PostsList{ ... }
        //   })
        // and
        //   template.RegisterComponent("Posts", postsSrc, func() template.Component {
        //       return &PostsList{}
        //   })
        // Captures 1: component name; 2: returned struct simple name.
        // Tolerant of whitespace/newlines and arbitrary content
        // inside the {} literal; the second group is the type name
        // right after `&`.
        private val REGISTER_RE = Regex(
            """(?:Register|RegisterComponent)\s*\(\s*"([^"]+)"\s*,[\s\S]*?return\s+&(\w+)\s*\{""",
        )

        // Matches `type Foo struct { …body… }` — top-level only
        // because our scanner doesn't track scopes. Body is up to
        // the matching `}` on the SAME indentation level; the
        // [^}]*? lazy form is wrong for nested fields with anonymous
        // structs, but those are rare in templated components.
        private val STRUCT_RE = Regex(
            """type\s+(\w+)\s+struct\s*\{([^}]*)\}""",
        )

        // Matches `func (c *Foo) Name(params) returnStuff {` and
        // `func (c Foo) Name(params) {`. Captures:
        //   1: receiver type without the leading * (Foo)
        //   2: method name (Name)
        //   3: raw parameter list text between the outer parens.
        // The parameter group uses [^)]* which can't represent a
        // function-typed param like `func(int) string` — those are
        // rare on live components and would just appear with their
        // outer signature truncated; the result is still useful for
        // both completion and navigation.
        private val METHOD_RE = Regex(
            """func\s*\(\s*\w+\s+\*?(\w+)\s*\)\s+(\w+)\s*\(([^)]*)\)""",
        )
    }
}