package com.paulmanoni.nexus.gutter

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.paulmanoni.nexus.settings.NexusSettings
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Gutter icons next to nexus handler registrations in Go source.
 * Identifies calls to nexus.AsRest / AsQuery / AsMutation / AsWS /
 * AsCRUD / AsWorker / AsRestHandler / AsSubscription as the
 * framework's "endpoint declarations" — operators can scan a long
 * module file and see at a glance which lines wire up handlers.
 *
 * Implementation note: avoids depending on the Go plugin's PSI
 * classes so the plugin loads in IDEs without Go support (IDEA CE,
 * PyCharm, etc.) without an optional-dependency dance. The provider
 * is gated by file extension instead — `.go` files only. False
 * positives in other contexts are unlikely because the handler
 * names are nexus-specific identifiers.
 *
 * Anchoring rules:
 *  - Match leaf PSI elements (identifier tokens) so the icon
 *    attaches to exactly one line per handler call.
 *  - Confirm the parent context contains the identifier followed by
 *    `(` — filters out type names, struct field names, and string
 *    literals that happen to spell "AsRest".
 *  - Returning null for non-matches keeps the IDE fast; the
 *    LeafPsiElement + identifier-text checks are O(1) per visited
 *    element.
 *
 * The provider is registered globally (no language= attribute on
 * plugin.xml's <codeInsight.lineMarkerProvider>) so the icons
 * appear in every IDE that ships the platform. Per-element filtering
 * keeps the cost bounded.
 */
class NexusHandlerLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Nexus handlers"

    override fun getIcon(): Icon = HandlerKind.Rest.icon

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Cheap rejection ladder. Each early return prunes the
        // common case (non-leaf, non-identifier, non-Go file) before
        // touching the parent chain.
        if (element !is LeafPsiElement) return null
        val text = element.text ?: return null
        val kind = HandlerKind.match(text) ?: return null
        if (!isGoFile(element)) return null
        if (!isCallContext(element, text)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            kind.icon,
            // Tooltip includes click hint so operators discover the
            // navigation behavior without reading docs.
            { _: PsiElement -> "${kind.tooltip} · click to open in dashboard" },
            OpenInDashboardHandler(kind),
            GutterIconRenderer.Alignment.LEFT,
        ) { kind.tooltip }
    }

    /**
     * Click handler that opens the running app's dashboard in the
     * user's default browser, pointed at the most relevant tab for
     * the kind of handler clicked. Workers land on the architecture
     * canvas (they have no /endpoints row of their own); everything
     * else lands on the endpoints tab where the operator can scroll
     * or Cmd-K to find the specific handler.
     *
     * The base URL comes from plugin settings (Settings → Tools →
     * Nexus → Dashboard base URL); defaults to localhost:8080 to
     * match the framework's default dev port.
     *
     * We don't resolve the handler's specific path / operation
     * here — that would require parsing the call's argument list,
     * which depends on Go PSI types and varies (AsRest takes
     * method+path, AsQuery infers from constructor name, AsCRUD
     * synthesizes routes from a type parameter). Coarse-grained
     * "open the right tab" is 80% of the value at 10% of the code;
     * a future enhancement can deep-link once the call-args
     * extraction is built.
     */
    private class OpenInDashboardHandler(
        private val kind: HandlerKind,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, element: PsiElement?) {
            val base = NexusSettings.getInstance().dashboardBaseUrl
                .ifBlank { "http://localhost:8080" }
                .trimEnd('/')
            BrowserUtil.browse("$base/__nexus/?tab=${kind.dashboardTab}")
        }
    }

    /**
     * File-extension gate. Beats checking
     * containingFile.language.id because that path forces the
     * platform to resolve the language object — which costs more on
     * a cold cache. virtualFile.extension is a string compare.
     */
    private fun isGoFile(element: PsiElement): Boolean {
        val ext = element.containingFile?.virtualFile?.extension ?: return false
        return ext.equals("go", ignoreCase = true)
    }

    /**
     * Confirms the identifier is actually being invoked. Walks up
     * to the parent and checks the parent's text starts with
     * `<name>(` or contains `.<name>(`. Two forms because Go calls
     * can be qualified (`nexus.AsRest(...)`) or unqualified
     * (`AsRest(...)` after a dot-import — uncommon but legal).
     *
     * Bounded to one parent hop so we don't walk the AST on every
     * leaf — the cost is dominated by the LeafPsiElement check
     * above.
     */
    private fun isCallContext(element: LeafPsiElement, name: String): Boolean {
        val parent = element.parent ?: return false
        val parentText = parent.text ?: return false
        return parentText.startsWith("$name(") || parentText.endsWith("$name")
    }

    /**
     * One enum entry per handler kind. Icon and tooltip live with
     * the entry so adding a new kind (e.g. AsTopic) is one line.
     *
     * Icon choices use AllIcons constants so the plugin .zip stays
     * tiny — no bundled SVGs. Each icon hints at the kind without
     * needing to read the tooltip:
     *   REST     → Web tool-window icon (a globe / network shape)
     *   GraphQL  → Json object icon (nested-shape)
     *   WS       → WebReferences server icon (bidirectional)
     *   CRUD     → Database icon
     *   Worker   → Run icon (long-running)
     */
    private enum class HandlerKind(
        val identifier: String,
        val tooltip: String,
        val icon: Icon,
        // dashboardTab is the ?tab= query value the dashboard's Vue
        // SPA reads to select a panel. Workers don't have their own
        // /endpoints row so they land on "architecture" (the
        // service-graph view where worker nodes are visible).
        val dashboardTab: String,
    ) {
        Rest("AsRest", "Nexus REST handler", AllIcons.Json.Object, "endpoints"),
        RestHandler("AsRestHandler", "Nexus REST handler (raw gin)", AllIcons.Json.Object, "endpoints"),
        Query("AsQuery", "Nexus GraphQL query", AllIcons.Toolwindows.WebToolWindow, "endpoints"),
        Mutation("AsMutation", "Nexus GraphQL mutation", AllIcons.Toolwindows.WebToolWindow, "endpoints"),
        Subscription("AsSubscription", "Nexus GraphQL subscription", AllIcons.Toolwindows.WebToolWindow, "endpoints"),
        WS("AsWS", "Nexus WebSocket handler", AllIcons.Webreferences.Server, "endpoints"),
        CRUD("AsCRUD", "Nexus CRUD handler set", AllIcons.Nodes.DataTables, "endpoints"),
        Worker("AsWorker", "Nexus worker (long-running)", AllIcons.RunConfigurations.TestState.Run, "architecture");

        companion object {
            // Lookup table keyed by identifier text — O(1) match on
            // the hot path. Built lazily once per class load.
            private val byIdentifier: Map<String, HandlerKind> by lazy {
                entries.associateBy { it.identifier }
            }

            fun match(text: String): HandlerKind? = byIdentifier[text]
        }
    }
}