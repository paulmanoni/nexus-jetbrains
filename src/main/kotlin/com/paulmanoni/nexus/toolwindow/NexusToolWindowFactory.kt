package com.paulmanoni.nexus.toolwindow

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.paulmanoni.nexus.settings.NexusSettings
import java.awt.BorderLayout
import java.awt.GridLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Side-panel tool window showing the live state of a running nexus
 * app: registered plugins, declared endpoints, and basic config.
 * Data comes from /__nexus/plugins and /__nexus/endpoints on the
 * dashboard base URL (defaults to localhost:8080; configurable).
 *
 * The window doesn't poll — operators click "Refresh" or open the
 * window to fetch. Polling would burn API requests when the IDE is
 * idle and create UI flicker; on-demand keeps the data current
 * enough for the typical "is my plugin wired?" check.
 */
class NexusToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = NexusToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Status", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * The tool window's body. Two stacked sections — plugins (top) and
 * endpoints (bottom) — separated by a refresh button row. Both
 * sections render plain text via JBLabel for v1; the structured
 * data shape can graduate to a JBTable when there's enough room to
 * justify it.
 */
private class NexusToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true) {

    private val pluginsLabel = JBLabel("Loading…").apply { verticalAlignment = JBLabel.TOP }
    private val endpointsLabel = JBLabel("Loading…").apply { verticalAlignment = JBLabel.TOP }
    private val statusLabel = JBLabel(" ")

    init {
        val refresh = JButton("Refresh").apply { addActionListener { refreshAsync() } }
        val toolbar = JPanel(BorderLayout()).apply {
            add(refresh, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        val plugins = JPanel(BorderLayout()).apply {
            add(JBLabel("<html><b>Plugins</b></html>"), BorderLayout.NORTH)
            add(JBScrollPane(pluginsLabel), BorderLayout.CENTER)
        }
        val endpoints = JPanel(BorderLayout()).apply {
            add(JBLabel("<html><b>Endpoints</b></html>"), BorderLayout.NORTH)
            add(JBScrollPane(endpointsLabel), BorderLayout.CENTER)
        }

        val body = JPanel(GridLayout(2, 1, 0, 4)).apply {
            add(plugins)
            add(endpoints)
        }

        setContent(JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        })

        // First fetch on window open so the operator doesn't stare
        // at "Loading…" until they click refresh.
        refreshAsync()
    }

    /**
     * Fetches /__nexus/plugins + /__nexus/endpoints on a background
     * thread (Application.executeOnPooledThread). Updates the UI
     * via SwingUtilities.invokeLater so we never touch Swing off
     * the EDT.
     */
    private fun refreshAsync() {
        val baseUrl = NexusSettings.getInstance().dashboardBaseUrl.ifBlank { "http://localhost:8080" }
        updateStatus("Fetching from $baseUrl…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val pluginsResult = fetchJSON<PluginsDocument>("$baseUrl/__nexus/plugins")
            val endpointsResult = fetchJSON<EndpointsDocument>("$baseUrl/__nexus/endpoints")
            SwingUtilities.invokeLater {
                applyPlugins(pluginsResult)
                applyEndpoints(endpointsResult)
                updateStatus("Last refresh: $baseUrl")
            }
        }
    }

    private fun applyPlugins(result: FetchResult<PluginsDocument>) {
        pluginsLabel.text = when (result) {
            is FetchResult.Ok -> renderPlugins(result.value.plugins)
            is FetchResult.Err -> "<html><i>error: ${result.message}</i></html>"
        }
    }

    private fun applyEndpoints(result: FetchResult<EndpointsDocument>) {
        endpointsLabel.text = when (result) {
            is FetchResult.Ok -> renderEndpoints(result.value.endpoints)
            is FetchResult.Err -> "<html><i>error: ${result.message}</i></html>"
        }
    }

    private fun renderPlugins(plugins: List<PluginRecord>): String {
        if (plugins.isEmpty()) return "<html>(no plugins registered)</html>"
        val rows = plugins.joinToString("<br>") { p ->
            val tags = buildList {
                if (p.hasDashboard) add("dashboard")
                if (p.hasClient) add("client")
                if (p.namespace.isNotBlank()) add("nx.${p.namespace}")
            }.joinToString(" · ")
            "&bull; <b>${p.name}</b> v${p.version.ifBlank { "?" }}  <span style='color:#888'>$tags</span>"
        }
        return "<html>$rows</html>"
    }

    private fun renderEndpoints(endpoints: List<EndpointRecord>): String {
        if (endpoints.isEmpty()) return "<html>(no endpoints registered)</html>"
        val rows = endpoints.joinToString("<br>") { e ->
            "&bull; <code>${e.method.ifBlank { "?" }}</code> ${e.path}  <span style='color:#888'>${e.service}</span>"
        }
        return "<html>$rows</html>"
    }

    private fun updateStatus(msg: String) {
        SwingUtilities.invokeLater { statusLabel.text = "  $msg" }
    }

    /**
     * Thin HTTP wrapper with a 5s timeout and a typed result wrap.
     * Returns the parsed payload on 2xx + valid JSON; an Err with a
     * readable message otherwise. Doesn't propagate exceptions so
     * the caller doesn't have to thread a try-catch through every
     * field update.
     */
    private inline fun <reified T> fetchJSON(url: String): FetchResult<T> {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() / 100 != 2) {
                return FetchResult.Err("HTTP ${response.statusCode()}")
            }
            FetchResult.Ok(Gson().fromJson(response.body(), T::class.java))
        } catch (e: Exception) {
            LOG.debug("nexus fetch failed for $url", e)
            FetchResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private sealed interface FetchResult<out T> {
        data class Ok<T>(val value: T) : FetchResult<T>
        data class Err(val message: String) : FetchResult<Nothing>
    }

    companion object {
        private val LOG = Logger.getInstance(NexusToolWindowPanel::class.java)
    }
}

// Wire payloads — match the framework's JSON shape exactly.

private data class PluginsDocument(val plugins: List<PluginRecord> = emptyList())

private data class PluginRecord(
    val name: String = "",
    val version: String = "",
    val namespace: String = "",
    @SerializedName("hasDashboard") val hasDashboard: Boolean = false,
    @SerializedName("hasClient") val hasClient: Boolean = false,
)

private data class EndpointsDocument(
    val endpoints: List<EndpointRecord> = emptyList(),
)

private data class EndpointRecord(
    val name: String = "",
    val method: String = "",
    val path: String = "",
    val service: String = "",
    val transport: String = "",
)