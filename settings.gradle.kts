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
// cannot resolve the neoforge-moddev-bundle variant it requires. The second, non-Fabric loader is Forge, built
// through ForgeGradle 6 on its own Gradle-8 island under forge/ (a separate Gradle build, since FG6 is
// Gradle-8-only and one build carries one wrapper). This root stays Gradle 9 and builds only common + fabric;
// the Forge jar is produced by forge/gradlew, not from here.
include("common", "fabric")
