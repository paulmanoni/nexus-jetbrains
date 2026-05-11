# nexus-jetbrains

JetBrains IDE plugin for the [nexus](https://github.com/paulmanoni/nexus) Go framework.

**v0.1.0** — initial release, tracking framework v0.43.x.

## Features

- **YAML linting** for `nexus.deploy.yaml` — every issue `nexus lint --yaml` would surface is shown inline in the editor, with severity-correct highlighting. Activates only on files literally named `nexus.deploy.yaml` (or `.yml`) so unrelated YAML in the project isn't affected.
- **Run configurations** — `Nexus Dev` runs `nexus dev` from the project root; `Nexus Lint` runs `nexus lint --yaml nexus.deploy.yaml`. Output streams to the standard Run tool window.
- **Status tool window** — right-side panel listing the running app's registered plugins + endpoints, fetched from `/__nexus/plugins` and `/__nexus/endpoints`. Refresh button; configurable base URL.

## Requirements

- IntelliJ Platform 2024.3 or later (every JetBrains IDE — IDEA, PyCharm, GoLand, Rider, etc.)
- A `nexus` CLI binary on PATH (or absolute path configured in Settings → Tools → Nexus)
- JDK 17+ for building

## Build / install

The repo ships without `gradle/wrapper/gradle-wrapper.jar` (binary, regenerable). After cloning:

```bash
# Materialize the wrapper. One-time bootstrap; commits the jar locally.
gradle wrapper --gradle-version 8.10.2

# Run a sandboxed IDE with the plugin loaded — the primary dev loop.
./gradlew runIde

# Build a distributable .zip for sideloading.
./gradlew buildPlugin
# → build/distributions/nexus-jetbrains-0.1.0.zip

# Pre-publish: run JetBrains' plugin verifier against recommended IDE builds.
./gradlew verifyPlugin
```

To install a built `.zip` into your real IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the file from `build/distributions/`.

## Settings

`Settings → Tools → Nexus`:

| Field | Default | Purpose |
|---|---|---|
| Nexus CLI path | `nexus` on PATH | Resolves the binary the lint annotator + run configs invoke. |
| Dashboard base URL | `http://localhost:8080` | Tool window fetches `/__nexus/plugins` + `/__nexus/endpoints` from here. |

## How the lint annotator works

The framework already ships [`nexus lint --yaml --json`](https://github.com/paulmanoni/nexus) (v0.43+). The plugin invokes it with the current editor buffer as input and renders each returned issue as an inline annotation:

- **Error** → red squiggle (blocks deploy if you run `nexus lint` in CI)
- **Warning** → yellow squiggle (advisory)

Path-to-offset mapping is best-effort substring matching — the CLI doesn't emit source positions (it operates on the parsed manifest), so the IDE rehydrates them by searching for the deepest identifier in the lint path. Works well for unique key names; falls back to the file start when nothing matches.

CLI failures (binary not found, timeout, malformed YAML) surface as a single banner annotation at the top of the file so you can't miss the misconfig.

## Layout

```
nexus-jetbrains/
├── build.gradle.kts                IntelliJ Platform Gradle Plugin 2.x
├── gradle.properties               Platform + plugin metadata
├── gradle/libs.versions.toml       Version catalog (Kotlin + IJ plugin)
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/paulmanoni/nexus/
    │   ├── lint/NexusLintExternalAnnotator.kt    YAML lint → editor markers
    │   ├── run/NexusRunConfigurations.kt         `nexus dev` + `nexus lint` configs
    │   ├── toolwindow/NexusToolWindowFactory.kt  Plugins + endpoints panel
    │   └── settings/NexusSettings.kt              Persisted CLI path + URL
    └── resources/META-INF/plugin.xml              Extension registration
```

## Roadmap

Tracked alongside the framework. Likely v0.2 additions:

- Schema completion + hover docs for `nexus.deploy.yaml` (bundled JSON Schema)
- Gutter icons next to `AsRest`/`AsQuery`/`AsMutation` declarations in Go source — click to open the matching dashboard endpoint
- "Generate `app.DeclareEnv(...)` from selected env var" quick-fix
- Live request log streaming from `/__nexus/events` into the tool window

## License

MIT — matches the framework's license.