package com.paulmanoni.nexus.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.paulmanoni.nexus.settings.NexusSettings
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Two run-configuration types backed by a single shared base:
 *
 *   - "Nexus Dev"  → runs `<cli> dev` from the project root, picks
 *                    up the user's nexus.deploy.yaml automatically.
 *   - "Nexus Lint" → runs `<cli> lint --yaml nexus.deploy.yaml`,
 *                    output streamed to the Run tool window.
 *
 * Both share the same minimal SettingsEditor (just a "no options"
 * placeholder for now) because the underlying CLI commands are
 * already configurable enough — the path comes from plugin settings
 * and the working directory is the project root.
 */
class NexusDevConfigurationType : ConfigurationTypeBase(
    "NexusDev",
    "Nexus Dev",
    "Runs `nexus dev` from the project root with the configured CLI.",
    com.intellij.icons.AllIcons.RunConfigurations.Application,
) {
    init {
        addFactory(NexusDevFactory(this))
    }
}

class NexusLintConfigurationType : ConfigurationTypeBase(
    "NexusLint",
    "Nexus Lint",
    "Runs `nexus lint --yaml nexus.deploy.yaml` and streams output to the Run tool window.",
    com.intellij.icons.AllIcons.RunConfigurations.Application,
) {
    init {
        addFactory(NexusLintFactory(this))
    }
}

class NexusDevFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "nexus-dev"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        NexusCliRunConfiguration(project, this, "Nexus Dev", listOf("dev"))
}

class NexusLintFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "nexus-lint"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        NexusCliRunConfiguration(
            project,
            this,
            "Nexus Lint",
            listOf("lint", "--yaml", "nexus.deploy.yaml"),
        )
}

/**
 * The actual run configuration. Holds the CLI subcommand list (dev
 * vs lint) and produces a GeneralCommandLine pointed at the
 * resolved CLI path + the project's working dir.
 *
 * Single class for both subcommands — they only differ in the args
 * passed at construction. Future per-config options (env vars,
 * extra flags) would extend the SettingsEditor below.
 */
class NexusCliRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
    private val args: List<String>,
) : RunConfigurationBase<RunProfileState>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        NexusCliConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): com.intellij.execution.process.ProcessHandler {
                val cli = NexusSettings.getInstance().cliPath.ifBlank { "nexus" }
                val cmd = GeneralCommandLine(listOf(cli) + args)
                    .withWorkDirectory(project.basePath)
                    .withCharset(Charsets.UTF_8)
                // ANSI on so the run tool window colorizes the CLI's
                // structured output (the framework's banner, gin's
                // request log, etc.).
                cmd.withEnvironment("TERM", "xterm-256color")
                return OSProcessHandler(cmd)
            }
        }
}

/**
 * Minimal SettingsEditor. The MVP version surfaces no per-config
 * fields — the CLI path lives in plugin settings (shared across
 * configs) and the args are fixed per type. Returns an empty JPanel
 * so the run-config dialog has *something* to render under the
 * "Configuration" tab.
 *
 * Future: add fields for working directory override, environment
 * variables, extra CLI flags.
 */
private class NexusCliConfigurationEditor : SettingsEditor<NexusCliRunConfiguration>() {
    private val panel = JPanel().apply {
        add(JLabel("No editable options. CLI path is configured in Settings → Tools → Nexus."))
    }

    override fun resetEditorFrom(s: NexusCliRunConfiguration) {}
    override fun applyEditorTo(s: NexusCliRunConfiguration) {}
    override fun createEditor(): JComponent = panel
}