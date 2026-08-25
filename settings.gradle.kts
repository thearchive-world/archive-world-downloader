pluginManagement {
    // Convention plugins (wdl.java-conventions, wdl.nullness-conventions, wdl.common-merge).
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal {
            content { excludeGroup("org.apache.logging.log4j") }
        }
        // :common is fully on loader-less Unimined now (no `alias(libs.plugins.loom)` applies anywhere on this
        // band), so this root has no plugin-marker resolution to do for Fabric, NeoForge, or Parchment; the
        // band's compileJava seam is the classic-MCP re-vocabularization (net.minecraft-facing source names
        // changed 1.13.2 -> 1.12.2), not a plugin-resolution failure.
        // The classic-MCP/Unimined set this band adds: MinecraftForge's own repo for the loader/universal jar,
        // plus WagYourTail's releases and snapshots hosting the Unimined plugin itself (1.4.1).
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://maven.wagyourtail.xyz/snapshots")
    }
}

rootProject.name = "wdl"

// NeoForge is dropped on this band: it does not exist for 1.12.x (NeoForged forked from Forge at 1.20.2), so the
// second, non-Fabric loader is Forge, built by the Forge island under forge/ (a separate Gradle build that
// provisions Forge natively through Unimined and reobfuscates natively, no hand-rolled Mojmap -> SRG step). This
// root builds only common; the Forge jar is produced by forge/gradlew, not from here.
include("common")
