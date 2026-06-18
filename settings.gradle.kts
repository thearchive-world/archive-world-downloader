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

include("common", "fabric", "neoforge")
