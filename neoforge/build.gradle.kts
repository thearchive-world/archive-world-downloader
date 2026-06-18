import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("net.neoforged.moddev")     // version comes from the root apply-false declaration
    id("com.diffplug.spotless")    // applied per-subproject, not via build-logic (see :common)
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("wdl.common-merge")
}

base {
    // -> archive-wdl-neoforge  (sibling to fabric's archive-wdl-fabric)
    archivesName.set("${property("mod_archives_base")}-neoforge")
}

// Spotless is applied per-subproject, not via build-logic: loaded from the included build, its
// Eclipse JDT formatter fails intermittently under the configuration cache + build cache + parallel
// execution, while from the root buildscript classpath it is stable. See :common for the full note.
spotless {
    java {
        target("src/**/*.java")
        eclipse("4.39").configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

neoForge {
    // NeoForge mode: set a NeoForge version (NOT a NeoForm version like common's Vanilla mode).
    // This puts NeoForge + Minecraft (Mojmap-named, no intermediary remap) on the classpath and
    // gives the client run type. The source-merge convention is provided by wdl.common-merge.
    version = property("neoforge_version") as String
    parchment {
        minecraftVersion = property("parchment_minecraft_version") as String
        mappingsVersion  = property("parchment_mappings_version") as String
    }

    // The mod = the main source set, which the source-merge augments with :common's classes.
    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets["main"])
        }
    }

    // A dev client run (optional; the manual gate uses an external launcher).
    runs {
        create("client") {
            client()
        }
    }
}

// The plain jar is the producing task; the guard is shared with the Fabric sibling (see build-logic).
registerVerifyProductionJar("jar")
