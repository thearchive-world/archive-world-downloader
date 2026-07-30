import net.ltgt.gradle.errorprone.errorprone

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

// --- core invariant: world.thearchive.wdl.core.** imports only from the allowlisted prefixes ---
// Keeps cross-branch cherry-picks of core viable as era-bands accrue. A fail-closed ALLOWLIST rather than
// a net.minecraft denylist: an MC-bundled library (com.mojang, gson, netty, joml,
// slf4j) compiles clean under both compileJava and the checkCoreJava8 floor compile (--release 8 accepts
// newer classfiles on the classpath), yet its presence and version vary per band, so it must not creep into
// core. A new core dependency is a deliberate amendment here, never an accident. It is a line-level import
// scan, wired into the check task.
val checkCoreImports = tasks.register("checkCoreImports") {
    group = "verification"
    description = "Fails if core/ imports anything outside the allowlisted prefixes"
    val allowedPrefixes = listOf("java.", "it.unimi.dsi.fastutil.", "org.jspecify.", "world.thearchive.wdl.core.")
    val coreFiles = layout.projectDirectory.dir("src/main/java/world/thearchive/wdl/core")
        .asFileTree.matching { include("**/*.java") }
    // Capture a plain File as a local here, not projectDir inside doLast: a Project accessor read
    // at execution time is a script-object reference the configuration cache cannot serialize.
    val baseDir = layout.projectDirectory.asFile
    inputs.property("allowedPrefixes", allowedPrefixes)
    inputs.files(coreFiles)
    doLast {
        val offenders = coreFiles.files.flatMap { file ->
            file.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) {
                    return@mapNotNull null
                }
                val imported = trimmed.removePrefix("import ").removePrefix("static ").trimStart().removeSuffix(";")
                if (allowedPrefixes.none { imported.startsWith(it) }) {
                    "  - ${file.relativeTo(baseDir)}: import $imported"
                } else {
                    null
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core invariant violated. core/ may import only ${allowedPrefixes.joinToString()} but found:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
// --- core invariant 2: world.thearchive.wdl.core.** must compile on the Java 8 floor ---
// core/ is cherry-picked byte-identical to the deep (<=1.16) bands, which run on Java 8, so it must use no
// Java 9+ language feature (record, var, pattern instanceof, switch expression) or JDK API (List.of,
// Optional.isEmpty, Stream.toList). checkCoreImports fences net.minecraft.*; this fences the language level by
// recompiling core under --release 8, which the band toolchain (21) would otherwise silently accept. --release
// (unlike source/target compatibility) restricts the visible JDK API, not just the bytecode version, so it
// catches API creep as well as syntax. Only enforced on the modern bands: a Java-8 band compiles core on JDK 8
// natively (the check is moot there, and --release postdates JDK 8 so it cannot run).
val checkCoreJava8 = tasks.register<JavaCompile>("checkCoreJava8") {
    group = "verification"
    description = "Fails if core/ uses any construct unavailable on the Java 8 floor"
    source = fileTree("src/main/java/world/thearchive/wdl/core") { include("**/*.java") }
    classpath = sourceSets["main"].compileClasspath
    destinationDirectory = layout.buildDirectory.dir("core-java8-check")
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
    // A plain floor compile: NullAway/Error Prone already ran on the real compileJava, so here only javac's
    // --release enforcement is wanted; leaving Error Prone on would run its default checks under --release 8.
    options.errorprone.enabled = false
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java_version").get().toInt())
    }
}
// --- band guard: the plug's declared era-band floor must cover the targeted minecraft_version ---
// A sibling of checkCoreImports above: a plug cherry-picked onto a branch whose minecraft_version predates
// its band fails here. The reverse (an older plug on a newer branch) is caught instead by compilation
// against the divergent vanilla save types.
val checkPlugBand = tasks.register("checkPlugBand") {
    group = "verification"
    description = "Fails if the plug's BAND_FLOOR is above the targeted minecraft_version"
    // A local inside this lambda, read via the project-scoped providers accessor. Two traps the
    // configuration cache springs here: a top-level val is a script-object field it cannot serialize
    // (so keep this local, captured by value in doLast), and bare property() would bind to the task
    // receiver (Task.property cannot see project properties), so go through providers.gradleProperty.
    val minecraftVersion = providers.gradleProperty("minecraft_version").get()
    val adapterSource = layout.projectDirectory
        .file("src/main/java/world/thearchive/wdl/adapter/impl/VersionAdapterImpl.java")
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.file(adapterSource)
    doLast {
        val floor = Regex("BAND_FLOOR\\s*=\\s*\"([^\"]+)\"")
            .find(adapterSource.asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException("VersionAdapterImpl.BAND_FLOOR not found; the plug declares no band")
        // Numeric per-component compare; lexical is wrong ("1.21.4" sorts above "1.21.10" as strings).
        val target = minecraftVersion.split(".").map(String::toInt)
        val base = floor.split(".").map(String::toInt)
        for (i in 0 until maxOf(target.size, base.size)) {
            val targetPart = target.getOrElse(i) { 0 }
            val floorPart = base.getOrElse(i) { 0 }
            if (targetPart != floorPart) {
                if (targetPart < floorPart) {
                    throw GradleException(
                        "plug band mismatch: minecraft_version=$minecraftVersion predates the plug's " +
                            "BAND_FLOOR=$floor (a plug must not land on a branch whose version predates its band)"
                    )
                }
                break
            }
        }
    }
}
tasks.named("check") {
    dependsOn(checkCoreImports, checkPlugBand)
    // The Java-8 floor compile is moot on a band whose toolchain already is Java 8 (see checkCoreJava8).
    if (providers.gradleProperty("java_version").get().toInt() > 8) {
        dependsOn(checkCoreJava8)
    }
}

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

dependencies {
    // XaeroPlus public API for the overlay binding (compat/xaeroplus), compile-only (never a runtime require).
    // common is ModDev in Mojmap mode, so pull the NeoForge (Mojmapped) flavor to match the classpath; the
    // loader subprojects add their own flavor for the source-merged compile.
    compileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+neoforge-${property("minecraft_version")}")

    // JourneyMap public API for the overlay binding (compat/journeymap), compile-only (never a runtime require).
    // The -common flavor carries no Mojmap/loader flavor split, so it is the correct pick here as well as in
    // the loader subprojects' source-merged compile.
    compileOnly("info.journeymap:journeymap-api-common:${property("journeymap_api_version")}-${property("minecraft_version")}")
}
