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

// NeoForge is dropped on this band: NeoForge 20.2.x publishes no Gradle module metadata, so ModDevGradle
// cannot resolve the neoforge-moddev-bundle variant it requires. The second loader is Forge, deferred to a
// dedicated later session; a Forge jar builds through ForgeGradle on its own Gradle-8 island, so 1.20.2 ships
// Fabric-only until then.
include("common", "fabric")
