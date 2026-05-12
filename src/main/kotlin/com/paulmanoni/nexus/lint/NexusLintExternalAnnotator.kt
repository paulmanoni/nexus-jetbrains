package com.paulmanoni.nexus.lint

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.paulmanoni.nexus.cli.resolveCliPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * Runs `nexus lint --yaml --json` against the open YAML buffer and
 * surfaces each issue as an editor annotation. Activates only on
 * files named `nexus.deploy.yaml` (or `.yml`) — other YAML buffers
 * in the same project are skipped so unrelated kubernetes / GitHub
 * Actions / docker-compose files don't trigger the linter.
 *
 * The annotator is a three-phase contract (IntelliJ Platform API):
 *
 *   collectInformation → doAnnotate → apply
 *
 * `collectInformation` runs on the EDT — it captures everything we
 * need from the PSI tree (file path, text content) into a plain
 * data class.
 *
 * `doAnnotate` runs on a background thread — safe to exec the CLI
 * and parse output without blocking the UI.
 *
 * `apply` runs on the EDT again — translates parsed issues into
 * [AnnotationHolder] calls. PSI access here must use runReadAction.
 */
class NexusLintExternalAnnotator :
    ExternalAnnotator<NexusLintExternalAnnotator.Snapshot, NexusLintExternalAnnotator.Result>() {

    /**
     * Per-invocation snapshot of the buffer + the resolved CLI
     * path. Computed once on the EDT; passed to doAnnotate where
     * VirtualFile access is forbidden.
     */
    data class Snapshot(
        val tempFile: Path,
        val text: String,
        val cliPath: String,
        val documentText: String,
    )

    /** doAnnotate output — flat list of issues + a transient error. */
    data class Result(
        val issues: List<Issue> = emptyList(),
        val cliError: String? = null,
    )

    /**
     * Mirrors the `--json` payload `nexus lint` emits. Field names
     * match the CLI's struct tags exactly so Gson maps cleanly.
     */
    data class LintDocument(
        val issues: List<Issue> = emptyList(),
        val summary: Summary = Summary(),
    )

    data class Issue(
        val severity: String = "error",
        val code: String = "",
        val path: String = "",
        val message: String = "",
    )

    data class Summary(
        val errors: Int = 0,
        val warnings: Int = 0,
    )

    override fun collectInformation(file: PsiFile): Snapshot? {
        val vfile: VirtualFile = file.virtualFile ?: return null
        if (!isNexusDeployYaml(vfile)) return null

        val cliPath = resolveCliPath()

        // Write the in-memory buffer to a temp file so the CLI sees
        // unsaved edits. Keeping a temp file (rather than piping
        // stdin) means the CLI's source label in error messages
        // matches what an operator running it on the saved file
        // would see — easier to translate between IDE warnings and
        // CI failures.
        val document: Document =
            com.intellij.psi.PsiDocumentManager
                .getInstance(file.project)
                .getDocument(file) ?: return null

        val tempFile = Files.createTempFile("nexus-lint-", "-${vfile.name}")
        Files.writeString(tempFile, document.text)

        return Snapshot(
            tempFile = tempFile,
            text = document.text,
            cliPath = cliPath,
            documentText = document.text,
        )
    }

    override fun doAnnotate(collectedInfo: Snapshot): Result {
        val (tempFile, _, cliPath, _) = collectedInfo
        try {
            val process = ProcessBuilder(cliPath, "lint", "--yaml", "--json", tempFile.absolutePathString())
                .redirectErrorStream(false)
                .start()

            // Hard timeout — a misconfigured CLI shouldn't pin a
            // background thread indefinitely. 10s is generous for
            // a YAML-only lint; the actual run is sub-100ms.
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(cliError = "nexus lint timed out after 10s")
            }

            val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val stderr = process.errorStream.readAllBytes().toString(Charsets.UTF_8)

            // Exit code 1 with valid JSON on stdout = "errors found";
            // that's the normal lint-failure path. Other non-zero
            // exit codes with no JSON = the CLI itself failed (bad
            // path, malformed YAML it couldn't even start parsing).
            if (stdout.isBlank()) {
                val msg = stderr.trim().ifBlank { "nexus lint exited ${process.exitValue()} with no output" }
                return Result(cliError = msg)
            }

            val doc = Gson().fromJson(stdout, LintDocument::class.java)
            return Result(issues = doc.issues)
        } catch (e: IOException) {
            return Result(cliError = "could not exec '$cliPath': ${e.message ?: e.javaClass.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(cliError = "interrupted")
        } catch (e: Exception) {
            LOG.warn("nexus lint annotation failed", e)
            return Result(cliError = e.message ?: e.javaClass.simpleName)
        } finally {
            // Best-effort temp cleanup. Leaving one behind on crash
            // is benign — OS cleans /tmp eventually.
            runCatching { Files.deleteIfExists(tempFile) }
        }
    }

    override fun apply(file: PsiFile, annotationResult: Result, holder: AnnotationHolder) {
        // CLI exec failures get one banner-style annotation at the
        // file's first character so the operator notices the
        // misconfiguration. They don't get a per-issue treatment —
        // the report would just repeat the same line forever.
        annotationResult.cliError?.let { msg ->
            holder.newAnnotation(HighlightSeverity.WARNING, "nexus lint unavailable: $msg")
                .range(TextRange(0, minOf(1, file.textLength)))
                .needsUpdateOnTyping(false)
                .create()
            return
        }

        val document = runReadAction {
            com.intellij.psi.PsiDocumentManager
                .getInstance(file.project)
                .getDocument(file)
        } ?: return

        for (issue in annotationResult.issues) {
            val range = locateRange(document, file.text, issue.path)
            val severity = when (issue.severity) {
                "error" -> HighlightSeverity.ERROR
                "warning" -> HighlightSeverity.WARNING
                else -> HighlightSeverity.WEAK_WARNING
            }
            val tooltip = buildString {
                append("nexus lint [")
                append(issue.code)
                append("]: ")
                append(issue.message)
            }
            holder.newAnnotation(severity, "${issue.path}: ${issue.message}")
                .range(range)
                .tooltip(tooltip)
                .needsUpdateOnTyping(true)
                .create()
        }
    }

    /**
     * Maps a dotted Lint path ("env.LOG_LEVEL.default", "overrides.qa")
     * to a [TextRange] in the YAML buffer. Best-effort: searches for
     * the deepest segment that appears as a literal substring; falls
     * back to the file start when nothing matches so the annotation
     * still appears.
     *
     * The CLI doesn't emit source positions because it operates on
     * Manifest JSON; the IDE rehydrates them via substring matching.
     * "Good enough" for the typical key names (LOG_LEVEL, JWT_SIGNING_KEY)
     * that are unique within the file.
     */
    private fun locateRange(document: Document, text: String, path: String): TextRange {
        if (path.isBlank() || document.textLength == 0) {
            return TextRange(0, minOf(1, document.textLength))
        }
        // Walk path from the leaf inward; the deepest matching
        // segment is the most specific anchor we can find.
        val segments = path.split('.', '[', ']').filter { it.isNotBlank() }
        for (segment in segments.reversed()) {
            val idx = text.indexOf(segment)
            if (idx >= 0) {
                val end = (idx + segment.length).coerceAtMost(document.textLength)
                return TextRange(idx, end)
            }
        }
        return TextRange(0, minOf(1, document.textLength))
    }

    private fun isNexusDeployYaml(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name == "nexus.deploy.yaml" || name == "nexus.deploy.yml"
    }

    companion object {
        private val LOG = Logger.getInstance(NexusLintExternalAnnotator::class.java)
    }
}