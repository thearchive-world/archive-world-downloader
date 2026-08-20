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

// NeoForge is dropped on this band: it does not exist for 1.13.x (NeoForged forked from Forge at 1.20.2), so the
// second, non-Fabric loader is Forge, built by a hand-rolled plain-java island under forge/ (a separate Gradle
// build with no ForgeGradle and no Loom: it compiles against the Mojmap Minecraft plus a Mojmap-named view of the
// real Forge 1.13.2-25.0.223 universal jar, reobfuscates Mojmap -> SRG by hand, and packages). This root builds
// only common; the Forge jar is produced by forge/gradlew, not from here.
include("common")

// The Mojmap bridge generator (tools/mojmap-bridge) is an included build, not a subproject: its genBridge task
// composes the intermediary->Mojmap mapping jar that common's Loom toolchain consumes. Included here (rather than
// only in pluginManagement, where build-logic sits) because it supplies a task-produced artifact, not a plugin;
// common wires the mapping with builtBy(gradle.includedBuild("mojmap-bridge").task(":genBridge")). The build name
// is the directory basename (mojmap-bridge), which differs from its rootProject.name (wdl-mojmap-bridge).
includeBuild("tools/mojmap-bridge")
