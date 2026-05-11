// Project settings — single-module plugin. The IntelliJ Platform
// Gradle Plugin pulls everything else in via the `intellijPlatform`
// extension in build.gradle.kts.

rootProject.name = "nexus-jetbrains"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // IntelliJ Platform Gradle Plugin 2.x is published here.
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}