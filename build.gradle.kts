// Build script for the nexus IntelliJ plugin. Uses IntelliJ Platform
// Gradle Plugin 2.x (the rewrite released in 2024, not the legacy
// `org.jetbrains.intellij` plugin) — it has a cleaner DSL, parallel
// builds, and direct support for the "platform" + "bundled" + "local"
// dependency declarations the plugin needs.

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )

        // Bundled plugin modules. yaml is required for the lint
        // ExternalAnnotator to attach to nexus.deploy.yaml files;
        // additional bundles (json, properties) can be added when
        // future features need them.
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins")
                .map { it.split(',').map(String::trim).filter(String::isNotEmpty) },
        )

        // Test framework — JUnit 5 via the Platform 2.x integration.
        testFramework(TestFrameworkType.Platform)
    }

    // JSON parsing for the `nexus lint --json` output. IntelliJ
    // bundles Gson via its platform classpath; declare as
    // compileOnly so the plugin .zip doesn't ship a duplicate.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // `runIde` task launches a sandboxed IDE with the plugin loaded;
    // primary dev loop. `verifyPlugin` runs JetBrains' compatibility
    // checker; useful pre-publish.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // pluginVerification {} block intentionally omitted from v0.1.0 —
    // ides.recommended() fetches the recommended-IDE list over the
    // network and can fail offline / in air-gapped builds. Add it
    // back when wiring up `verifyPlugin` in CI, where the network
    // path is guaranteed.
}

tasks {
    test {
        useJUnitPlatform()
    }

    // Wrapper task pinned to a known-good Gradle version. Run
    // `./gradlew wrapper` once after cloning to materialize
    // gradle/wrapper/* — the .jar is binary and isn't tracked
    // in the initial scaffold.
    wrapper {
        gradleVersion = "8.10.2"
        distributionType = Wrapper.DistributionType.BIN
    }
}