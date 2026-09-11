pluginManagement {
    // Convention plugins (wdl.java-conventions, wdl.nullness-conventions, wdl.common-merge).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.parchmentmc.org")
    }
}

rootProject.name = "wdl"

// NeoForge is dropped on this band: its only 1.20.1 line is the transitional 47.1.x fork, published as
// net.neoforged:forge and by NeoForged's own account still compatible with existing Forge mods, so the Forge jar
// built here covers it. The second, non-Fabric loader is Forge, built by the Forge island under forge/ (a separate
// Gradle build with its own wrapper). This root builds only common + fabric; the Forge jar comes from forge/gradlew.
include("common", "fabric")
