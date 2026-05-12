package com.paulmanoni.nexus.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.*
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.util.ui.FormBuilder
import com.paulmanoni.nexus.cli.resolveCliPath
import org.jdom.Element
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Two run-configuration types backed by a single shared
 * NexusCliRunConfiguration. They differ only in their base CLI args
 * — "dev" and "lint --yaml nexus.deploy.yaml" — and in which option
 * fields make sense (lint takes a manifest path; dev takes extra
 * flags like --split). The shared base + per-type defaults keep the
 * code small while still letting operators customize per-config.
 */
class NexusDevConfigurationType : ConfigurationTypeBase(
    "NexusDev",
    "Nexus Dev",
    "Runs `nexus dev` from the project root.",
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
 * One run configuration class for both types. baseArgs are the
 * subcommand + default flags the factory pre-seeded; extraArgs are
 * operator-typed additions that come after.
 *
 * Fields persisted across IDE restarts via writeExternal /
 * readExternal: workingDir, extraArgs, env. baseArgs travel with
 * the factory (the user's choice of "Dev" vs "Lint") and don't need
 * persisting — re-derived from the configuration's factory id.
 */
class NexusCliRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
    private val baseArgs: List<String>,
) : RunConfigurationBase<RunProfileState>(project, factory, name) {

    /** Working directory for the spawned process. Empty = project root. */
    var workingDirectory: String = ""

    /** Extra CLI flags / args appended after the base subcommand. */
    var extraArgs: String = ""

    /** Environment variables overlaid on the system env. */
    var envVars: MutableMap<String, String> = mutableMapOf()

    /**
     * If true, the system env is also passed through (typical).
     * Operators occasionally want a hermetic env for repro — flip
     * this off and only [envVars] are visible to the process.
     */
    var passParentEnvs: Boolean = true

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        NexusCliConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): com.intellij.execution.process.ProcessHandler {
                val cli = resolveCliPath()
                val extra = ParametersList.parse(extraArgs.trim())
                val cmd = GeneralCommandLine(listOf(cli) + baseArgs + extra)
                    .withWorkDirectory(resolveWorkingDir())
                    .withCharset(Charsets.UTF_8)
                cmd.withEnvironment(envVars)
                cmd.withParentEnvironmentType(
                    if (passParentEnvs)
                        GeneralCommandLine.ParentEnvironmentType.CONSOLE
                    else
                        GeneralCommandLine.ParentEnvironmentType.NONE
                )
                // ANSI on so the run tool window colorizes structured
                // CLI output (gin request log, framework banner).
                if (cmd.environment["TERM"].isNullOrEmpty()) {
                    cmd.withEnvironment("TERM", "xterm-256color")
                }
                return OSProcessHandler(cmd)
            }
        }

    /**
     * Resolves the configured workingDirectory, falling back to the
     * project base path. Empty / blank / nonexistent paths all
     * collapse to the project root so a misconfigured field never
     * crashes the run.
     */
    private fun resolveWorkingDir(): String {
        val configured = workingDirectory.trim()
        if (configured.isNotEmpty() && java.io.File(configured).isDirectory) {
            return configured
        }
        return project.basePath ?: System.getProperty("user.home")
    }

    /**
     * Persist fields into the run-configuration .xml IDE writes
     * under .idea/runConfigurations/. Standard pattern for run-
     * config state — JDOMExternalizerUtil keeps the format
     * forward/backward compatible with IDE versions.
     */
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "workingDirectory", workingDirectory)
        JDOMExternalizerUtil.writeField(element, "extraArgs", extraArgs)
        JDOMExternalizerUtil.writeField(element, "passParentEnvs", passParentEnvs.toString())
        EnvironmentVariablesComponent.writeExternal(element, envVars)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        workingDirectory = JDOMExternalizerUtil.readField(element, "workingDirectory", "")
        extraArgs = JDOMExternalizerUtil.readField(element, "extraArgs", "")
        passParentEnvs = JDOMExternalizerUtil
            .readField(element, "passParentEnvs", "true")
            .toBoolean()
        envVars.clear()
        EnvironmentVariablesComponent.readExternal(element, envVars)
    }

    /**
     * Read-only accessor for the base args — needed by the editor to
     * render the "this is the command we'll run" preview hint.
     */
    fun baseArgsPreview(): String = baseArgs.joinToString(" ")
}

/**
 * Settings editor with real fields:
 *
 *   Base command (read-only)   — shows the subcommand the factory
 *                                pinned ("dev", "lint --yaml ...")
 *   Extra arguments            — appended after the base; supports
 *                                shell-style quoting via ParametersList
 *   Working directory          — file chooser, defaults to project root
 *   Environment variables      — JetBrains' standard env editor with
 *                                checkbox for "pass parent env"
 *
 * Each field is bound to a corresponding NexusCliRunConfiguration
 * property in resetEditorFrom / applyEditorTo so changes persist.
 */
private class NexusCliConfigurationEditor : SettingsEditor<NexusCliRunConfiguration>() {
    private val baseCommandField = JBTextField().apply {
        isEditable = false
        // Slightly dimmed to read as "informational, not editable".
        putClientProperty("StatusBar.IconBorder.disabled", true)
    }
    private val extraArgsField = RawCommandLineEditor().apply {
        dialogCaption = "Extra Arguments"
    }
    private val workingDirField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Empty = project root"
    }
    private val envComponent = EnvironmentVariablesComponent()

    init {
        // Wire the working-dir file chooser to pick directories only.
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        workingDirField.addActionListener {
            val file = FileChooser.chooseFile(descriptor, workingDirField, null, null)
            file?.let { workingDirField.text = FileUtil.toSystemDependentName(it.path) }
        }
    }

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Base command:", baseCommandField, 1, false)
        .addTooltip("Set by the run configuration type (Nexus Dev / Lint). Not editable per-config — the type's identity.")
        .addLabeledComponent("Extra arguments:", extraArgsField, 1, false)
        .addTooltip("Appended after the base command. Shell-style quoting works (--flag=\"value with spaces\").")
        .addLabeledComponent("Working directory:", workingDirField, 1, false)
        .addTooltip("Where the process runs. Empty = project root.")
        .addComponent(envComponent)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun resetEditorFrom(s: NexusCliRunConfiguration) {
        baseCommandField.text = "nexus ${s.baseArgsPreview()}"
        extraArgsField.text = s.extraArgs
        workingDirField.text = s.workingDirectory
        envComponent.envs = s.envVars
        envComponent.isPassParentEnvs = s.passParentEnvs
    }

    override fun applyEditorTo(s: NexusCliRunConfiguration) {
        s.extraArgs = extraArgsField.text
        s.workingDirectory = workingDirField.text.trim()
        s.envVars = envComponent.envs.toMutableMap()
        s.passParentEnvs = envComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel
}

// Unused imports kept for clarity over what's needed; trimmed below.
@Suppress("unused")
private val _imports = listOf(
    StringUtil::class.java,
    ExtendableTextField::class.java,
)