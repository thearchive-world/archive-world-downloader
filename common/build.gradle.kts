plugins {
    id("net.neoforged.moddev")     // version comes from the root apply-false declaration
    id("com.diffplug.spotless")    // applied per-subproject, not via build-logic (see below)
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
}

// Spotless owns mechanical layout, per the shared JDT profile in config/spotless/eclipse-formatter.prefs. It is
// applied here per-subproject rather than via the build-logic convention plugin: loaded from the
// included build, its Eclipse JDT formatter fails intermittently (InvocationTargetException) under
// the configuration cache + build cache + parallel execution, while from the root buildscript
// classpath it is stable. The block is duplicated across the three subprojects for that reason.
spotless {
    java {
        target("src/**/*.java")
        eclipse("4.39").configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

neoForge {
    // Vanilla mode: set a NeoForm version (NOT a NeoForge version)
    neoFormVersion = property("neoform_version") as String
    parchment {
        minecraftVersion = property("parchment_minecraft_version") as String
        mappingsVersion  = property("parchment_mappings_version") as String
    }
    // Vanilla mode supports only client/server/data run types (no loader); common needs none.
}

// --- headless unit tests (JUnit 5) with real Minecraft on the TEST classpath ---
// MDG Vanilla-mode puts net.minecraft.* (classes + bundled vanilla data) on the MAIN classpath;
// addModdingDependenciesTo wires those same modding deps into the test source set so plain JUnit
// tests can boot vanilla registries headlessly. No gametest/loader harness. These are pure JUnit.
// JUnit itself comes from wdl.java-conventions.
neoForge.addModdingDependenciesTo(sourceSets["test"])

// --- Expose common's main source + resources for source-merge into loader subprojects ---
// MultiLoader-Template "commonJava"/"commonResources" pattern:
// the loader (e.g. :fabric) consumes these and compiles common's SOURCE directly into its jar,
// so common's classes are remapped by Loom alongside the loader's own classes. The consumer side
// lives in the wdl.common-merge convention plugin.
val commonJava = configurations.create("commonJava") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val commonResources = configurations.create("commonResources") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add("commonJava", file("src/main/java"))
    add("commonResources", file("src/main/resources"))
}
