package com.paulmanoni.nexus.schema

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Wires up JetBrains' bundled YAML / JSON Schema integration so any
 * file named nexus.deploy.yaml (or .yml) gets schema-driven editor
 * features for free:
 *
 *   - autocomplete on every key (deployments, peers, environments,
 *     secrets, files, hooks, environment_overrides, ...)
 *   - hover docs from each property's "description" field
 *   - inline validation against types / enums / regex
 *   - quick-fix "remove unknown property" suggestions
 *
 * Implementation: the schema lives at
 * resources/schemas/nexus-deploy.schema.json (bundled in the
 * plugin .zip). At runtime we resolve it via the plugin's class
 * loader and hand it to JetBrains' framework. The framework owns
 * the rest — parsing, type inference, completion suggestions.
 *
 * Schema scope is the strict "embedded" type: applies only to files
 * matching isAvailable() below, never to other YAML in the project.
 * The lint annotator uses the same filename predicate so the two
 * surfaces stay aligned.
 */
class NexusYamlSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(NexusDeploySchemaProvider())
}

private class NexusDeploySchemaProvider : JsonSchemaFileProvider {

    override fun getName(): String = "nexus.deploy.yaml"

    /**
     * The schema applies to files literally named nexus.deploy.yaml
     * or nexus.deploy.yml. Other YAML in the project (kubernetes,
     * docker-compose, GitHub Actions) stays untouched.
     */
    override fun isAvailable(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name == "nexus.deploy.yaml" || name == "nexus.deploy.yml"
    }

    /**
     * Returns the bundled schema file from the plugin's resources.
     * The classpath URL is resolved through VfsUtil so JetBrains'
     * caching layer recognizes it as a real VirtualFile (not a
     * synthetic one) — needed for hover / goto-definition support.
     */
    override fun getSchemaFile(): VirtualFile? {
        val url = NexusDeploySchemaProvider::class.java
            .getResource(SCHEMA_RESOURCE_PATH)
            ?: return null
        return VfsUtil.findFileByURL(url)
    }

    /**
     * Schema type: embedded means "ships with the plugin, the user
     * doesn't pick it from Settings". JetBrains shows it as a
     * read-only entry in the JSON Schema Mappings panel.
     */
    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    /**
     * Marketplace / docs URL — surfaces in the JSON Schema status
     * bar as a clickable link. Optional; nice-to-have.
     */
    override fun getRemoteSource(): String? =
        "https://github.com/paulmanoni/nexus"

    private companion object {
        const val SCHEMA_RESOURCE_PATH = "/schemas/nexus-deploy.schema.json"
    }
}