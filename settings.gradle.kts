pluginManagement {
    // Convention plugins (wdl.java-conventions, wdl.nullness-conventions).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal {
            content { excludeGroup("org.apache.logging.log4j") }
        }
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://maven.wagyourtail.xyz/snapshots")
    }
}

rootProject.name = "wdl"

// NeoForge has no release below 1.20.1, so the loader here is Forge, built by the Forge island under forge/ (a
// separate Gradle build that provisions Forge natively through Unimined and reobfuscates natively, no hand-rolled
// Mojmap -> SRG step). This root builds only common; the Forge jar is produced by forge/gradlew, not from here.
include("common")
