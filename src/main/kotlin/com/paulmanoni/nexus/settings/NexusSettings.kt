package com.paulmanoni.nexus.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Application-level settings persisted under the IDE's options dir.
 * Two knobs only — keep the config surface minimal until features
 * actually demand more:
 *
 *   cliPath          → executable invoked by the lint annotator
 *                      and the run configurations. Empty = "nexus"
 *                      on PATH.
 *   dashboardBaseUrl → base URL for the tool window's HTTP fetches
 *                      against /__nexus/plugins + /__nexus/endpoints.
 *                      Empty = "http://localhost:8080".
 *
 * [State] is the serialization shape; the class itself owns
 * defaults + the getInstance accessor.
 */
@Service(Service.Level.APP)
@State(
    name = "NexusSettings",
    storages = [Storage("nexus.xml")],
)
class NexusSettings : PersistentStateComponent<NexusSettings.State> {
    data class State(
        var cliPath: String = "",
        var dashboardBaseUrl: String = "",
    )

    private var state = State()

    var cliPath: String
        get() = state.cliPath
        set(value) { state.cliPath = value.trim() }

    var dashboardBaseUrl: String
        get() = state.dashboardBaseUrl
        set(value) { state.dashboardBaseUrl = value.trim().trimEnd('/') }

    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun getInstance(): NexusSettings =
            ApplicationManager.getApplication().getService(NexusSettings::class.java)
    }
}

/**
 * Settings UI exposed under Settings → Tools → Nexus. Two text
 * fields wired to NexusSettings; modified/apply/reset implement
 * the standard Configurable contract so the "Apply" button only
 * lights up when a value actually changed.
 */
class NexusConfigurable : Configurable {
    private val cliPathField = JBTextField()
    private val baseUrlField = JBTextField()

    override fun getDisplayName(): String = "Nexus"

    override fun createComponent(): JComponent {
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Nexus CLI path:"), cliPathField, 1, false)
            .addTooltip("Absolute path to the `nexus` binary, or blank to use PATH.")
            .addLabeledComponent(JBLabel("Dashboard base URL:"), baseUrlField, 1, false)
            .addTooltip("Base URL the tool window hits for /__nexus/plugins + /__nexus/endpoints (default http://localhost:8080).")
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return panel
    }

    override fun isModified(): Boolean {
        val s = NexusSettings.getInstance()
        return cliPathField.text != s.cliPath || baseUrlField.text != s.dashboardBaseUrl
    }

    override fun apply() {
        val s = NexusSettings.getInstance()
        s.cliPath = cliPathField.text
        s.dashboardBaseUrl = baseUrlField.text
    }

    override fun reset() {
        val s = NexusSettings.getInstance()
        cliPathField.text = s.cliPath
        baseUrlField.text = s.dashboardBaseUrl
    }
}