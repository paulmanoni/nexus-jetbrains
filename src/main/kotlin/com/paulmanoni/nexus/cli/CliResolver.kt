package com.paulmanoni.nexus.cli

import com.paulmanoni.nexus.settings.NexusSettings
import java.io.File

/**
 * Resolves the `nexus` CLI binary for invocation by the lint
 * annotator + run configurations.
 *
 * macOS gotcha that prompted this code: JetBrains IDEs launched from
 * the Dock / Launchpad / Finder inherit only the system-default
 * PATH (`/usr/bin:/bin:/usr/sbin:/sbin`), NOT the user's shell PATH.
 * A `nexus` binary installed via `go install` (lands in `~/go/bin`)
 * or `brew install` (`/opt/homebrew/bin`) is invisible to a
 * ProcessBuilder spawned from the IDE — even though `which nexus`
 * in a terminal finds it just fine.
 *
 * The resolver handles this in priority order:
 *
 *   1. User-configured absolute path (Settings → Tools → Nexus).
 *      Trumps everything else.
 *   2. User-configured bare name (e.g. "mynexus") → resolved via
 *      `which`-style search across the common install locations.
 *   3. Default "nexus" → same search as (2).
 *   4. Bare "nexus" fallback — ProcessBuilder will throw if the
 *      shell PATH doesn't have it, and the lint annotator surfaces
 *      the error as a banner so the operator can fix it.
 *
 * The search locations cover the typical Go-developer-on-macOS
 * setup: `$GOPATH/bin`, Homebrew, /usr/local. Linux paths are
 * included for completeness.
 */
object CliResolver {

    /**
     * Common Go / package-manager binary directories the IDE
     * launcher's bare-bones PATH typically misses. Ordered most-
     * likely-first so the search returns fast in the common case.
     */
    private val SEARCH_DIRS = listOf(
        // Go installs via `go install` land in $GOPATH/bin. Most
        // developers don't set GOPATH so it defaults to ~/go.
        "${System.getProperty("user.home")}/go/bin",
        // Homebrew Apple Silicon
        "/opt/homebrew/bin",
        // Homebrew Intel + manual /usr/local installs
        "/usr/local/bin",
        // XDG-style user-local
        "${System.getProperty("user.home")}/.local/bin",
    )

    /**
     * Returns the path the plugin should hand to ProcessBuilder.
     * Settings-supplied absolute paths pass through unchanged; bare
     * names trigger the search across [SEARCH_DIRS]. Always returns
     * SOMETHING — at worst the bare name, which lets the lint
     * annotator surface a clear banner about the missing binary.
     */
    fun resolve(): String {
        val configured = NexusSettings.getInstance().cliPath.trim()
        if (configured.isNotEmpty()) {
            // Absolute path or any path containing '/' is taken
            // verbatim — the operator was explicit.
            if (configured.contains('/')) {
                return configured
            }
            // Bare name — search for it.
            return findOnDisk(configured) ?: configured
        }
        return findOnDisk("nexus") ?: "nexus"
    }

    /**
     * Walks the SEARCH_DIRS looking for an executable file named
     * [name]. Returns the first match or null. Symbolic links are
     * resolved by canExecute() so a symlink in $GOPATH/bin pointing
     * to a non-existent target is skipped.
     */
    private fun findOnDisk(name: String): String? {
        for (dir in SEARCH_DIRS) {
            val candidate = File(dir, name)
            if (candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }
}

/**
 * Top-level alias so callers can write `resolveCliPath()` without
 * importing the object. Matches the existing call site style in
 * NexusLintExternalAnnotator.
 */
fun resolveCliPath(): String = CliResolver.resolve()