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

// NeoForge is dropped on this band: it does not exist for 1.19.x (NeoForged forked from Forge at 1.20.2), so the
// second, non-Fabric loader is Forge, built by the Forge island under forge/ (a separate Gradle build with its own
// wrapper). This root builds only common + fabric; the Forge jar is produced by forge/gradlew, not from here.
include("common", "fabric")
