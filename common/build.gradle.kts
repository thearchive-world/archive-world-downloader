import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("net.neoforged.gradle.vanilla") version "7.1.38" // common's Minecraft toolchain, versioned inline
    id("com.diffplug.spotless")    // applied per-subproject, not via build-logic (see below)
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("info.solidsoft.pitest") version "1.19.0" // on-demand mutation testing; common-only, so versioned inline
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

// --- Minecraft toolchain: NeoGradle vanilla, not ModDevGradle Vanilla mode ---
// NeoForm has no 1.20.1 release: NeoForged forked from Forge at 1.20.2, so net.neoforged:neoform starts there,
// and ModDevGradle Vanilla mode (which resolves that coordinate) cannot target this band. NeoGradle's vanilla
// plugin builds the same Mojmap (official) Minecraft on Gradle 9 by deriving it from mcp_config 1.20.1 instead,
// the exact namespace common is written in, with no intermediary remap. The net.minecraft:client distribution
// carries the shared and server-side classes the codec reads plus the bundled vanilla data pack, and an
// implementation dependency is inherited by the test source set, so the headless JUnit suite boots the vanilla
// registries against it with no extra wiring (the analog of MDG Vanilla mode's addModdingDependenciesTo(test)).
// Parchment is not layered on this band's common: NeoGradle vanilla takes no Parchment coordinate here, so the
// decompiled Minecraft carries generated parameter names, which WDL never references; fabric keeps its own
// Loom-layered Parchment separately.
dependencies {
    implementation("net.minecraft:client:${property("minecraft_version")}")
}

// --- PITest mutation testing: on-demand fidelity-gap discovery (./gradlew :common:pitest) ---
// Mutation testing perturbs production bytecode one change at a time (negate a conditional, drop a void
// call, swap a return) and re-runs the covering tests. A mutant that no test kills (a "survivor") marks a
// line whose behavior the suite exercises but does not actually pin: exactly a fidelity test that stays
// green while the save/encode logic it covers is silently broken. The survivor report is the deliverable.
//
// Scope is a fail-closed ALLOWLIST of the durable, cross-band fidelity surface (the core/ pure-logic and
// adapter/ deterministic-transform classes), never a package glob. A glob would fail open: it would
// auto-enroll every future IO/session class the moment it lands and drown the real survivors in
// equivalent-mutant noise from per-band glue. The allowlist fails closed instead, so a new class gets no
// mutants until deliberately enrolled; PitestAllowlistEnrollmentTest is what keeps that honest, failing the
// build when a new core/adapter class is neither enrolled here nor explicitly acknowledged-excluded.
//
// The minion JVMs inherit sourceSets.test.runtimeClasspath (net.minecraft + the bundled vanilla data pack
// from the net.minecraft:client implementation above), so registry-booting tests run in the minion exactly as under :test.
// CI-gated by .github/workflows/mutation-testing.yml on dev and version-branch pushes; a new survivor is
// triaged (killed, suppressed, or rewritten away) rather than absorbed by lowering the floor.
pitest {
    pitestVersion = "1.22.1"
    junit5PluginVersion = "1.2.2" // required: the test platform is JUnit 5; this is PIT's JUnit-Platform bridge

    // The fidelity-critical registry. Exact FQNs, never a package glob (see the rationale above).
    targetClasses.set(listOf(
        "world.thearchive.wdl.core.CaptureController",
        "world.thearchive.wdl.core.CaptureOrder",
        "world.thearchive.wdl.core.CaptureToggles",
        "world.thearchive.wdl.core.ContainerAssociation",
        "world.thearchive.wdl.core.CrafterSlots",
        "world.thearchive.wdl.core.EntityMenuCapability",
        "world.thearchive.wdl.core.MapManifest",
        "world.thearchive.wdl.core.MapHash",
        "world.thearchive.wdl.core.RecapturePolicy",
        "world.thearchive.wdl.core.RegionMath",
        "world.thearchive.wdl.core.WdlConfig",
        "world.thearchive.wdl.core.FlushPolicy",
        "world.thearchive.wdl.core.CaptureStatus",
        "world.thearchive.wdl.core.SendRangeEstimator",
        "world.thearchive.wdl.core.CoveredChunkIndex",
        "world.thearchive.wdl.core.SendRangeSampler",
        "world.thearchive.wdl.core.ChunkRectangleReducer",
        "world.thearchive.wdl.core.ReadyLatch",
        "world.thearchive.wdl.core.RegionChunkScan",
        "world.thearchive.wdl.core.OpenClickIntent",
        "world.thearchive.wdl.core.OutlineClassifier",
        "world.thearchive.wdl.core.SavedChunkIndex",
        "world.thearchive.wdl.core.SpectatorCrosshairFallback",
        "world.thearchive.wdl.core.VoidChunkPolicy",
        "world.thearchive.wdl.core.WorldOutputConfig",
        // Subpackage enrollments (core.report, adapter.impl): the PitestAllowlistEnrollmentTest registry
        // guard reaches only top-level core/ and adapter/ classes, so these are enrolled by hand; its
        // source-file check still fails the build if an entry here stops resolving to a production class.
        "world.thearchive.wdl.core.report.DownloadReportFormatter",
        "world.thearchive.wdl.core.report.SaveChunks",
        "world.thearchive.wdl.core.report.Json",
        "world.thearchive.wdl.adapter.CapturedBlockField",
        "world.thearchive.wdl.adapter.ChunkFlushPlan",
        "world.thearchive.wdl.adapter.ChunkMerge",
        "world.thearchive.wdl.adapter.ContainerMerge",
        "world.thearchive.wdl.adapter.EntityMerge",
        "world.thearchive.wdl.adapter.EntityContainerMerge",
        "world.thearchive.wdl.adapter.EntityTreeWalk",
        "world.thearchive.wdl.adapter.MapIdRemap",
        "world.thearchive.wdl.adapter.MapIdCollector",
        "world.thearchive.wdl.adapter.ItemLocationScrub",
        "world.thearchive.wdl.adapter.MerchantOfferCapture",
        "world.thearchive.wdl.adapter.BookshelfSlots",
        "world.thearchive.wdl.adapter.ItemTreeWalk",
        "world.thearchive.wdl.adapter.NbtMerge",
        "world.thearchive.wdl.adapter.PlayerProgressSerializer",
        "world.thearchive.wdl.adapter.PlayerTag",
        "world.thearchive.wdl.adapter.RecoveredScan",
        "world.thearchive.wdl.adapter.VanillaDimensions",
        "world.thearchive.wdl.adapter.impl.NaturalEquipment",
    ))

    // Every common test is a candidate killer; PIT's coverage analysis selects the ones that touch each
    // mutated line. This is NOT the plugin default (which mirrors targetClasses by name and would miss a
    // killer whose test name does not match the class under test, e.g. EntityContainerMerge is pinned by
    // EntityContainerStashMergeTest, a name PIT would never derive from the class). The *Test suffix is
    // load-bearing, not decoration: PIT loads every class this glob matches to discover its test units, and a
    // bare world.thearchive.wdl.* also matches production classes. A production class that names a compile-only
    // type (the compat/ bindings name JourneyMap and XaeroPlus API types absent at test runtime) then throws
    // NoClassDefFoundError during discovery, which PIT counts as a non-green baseline and aborts the whole run.
    // Anchoring on *Test keeps the discovery set to actual test classes (all of ours end in Test; no production
    // class does), which is exactly the candidate-killer set with no production class swept in.
    targetTests.set(listOf("world.thearchive.wdl.*Test"))

    // Four methods suppress five classified-equivalent-or-uncoverable mutants at method granularity (the only
    // mutants no portable test can kill; addDiscInto covers two symmetric-offset mutants under one reason). All
    // four method names are unique across the targetClasses,
    // so the name globs hit only the intended methods. MapManifest's two idempotent max-assignments are absent
    // from this list on purpose: written as Math.max in source, both arms of each are drivable by a test, so
    // they need no exclusion at all. Never widen this to swallow a real survivor; strengthen the covering test
    // instead.
    //   floorSliceSize: the empty-hot-set guard (hotCount <= 0) is equivalent under ConditionalsBoundary
    //     because the ceil-division fall-through already yields 0 at hotCount == 0.
    //   mergeOne: the block-entity scan bound (i < size) is equivalent under ConditionalsBoundary because the
    //     off-by-one only fires on the no-match path, whose extra read is absorbed by the per-entry fail-soft
    //     catch in mergeStashWith, leaving the merged count and the chunk tag identical.
    //   schemeMismatch: the fail-soft catch (return false on a genuine disk-read IOException) cannot be
    //     triggered by a portable unit test, since a readable temp directory never makes Files.list throw and
    //     the Windows target ignores POSIX permission tricks, so its return-false mutant has no coverage. The
    //     reachable scheme logic is fully covered by MapManifestTest.schemeMismatchNeedsImagedDataAndModeDifference.
    //   addDiscInto: flipping either centerX + dx or centerZ + dz to a subtraction under MathMutator is
    //     equivalent, because dx and dz each range over the symmetric interval from negative radius to radius
    //     and the inclusion test dx * dx + dz * dz <= radiusSquared is unchanged by negating either offset, so
    //     the double loop assembles the identical final chunk set either way.
    excludedMethods.set(listOf("floorSliceSize", "mergeOne", "schemeMismatch", "addDiscInto"))

    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports = false

    // Ratchet gate: fail the build on any survivor. The floor and the score are the same number, so a run that
    // passes is a run where every mutant the allowlist generates was killed. A new survivor is triaged (killed,
    // suppressed with a reason, or rewritten away) rather than absorbed by lowering this; never lower it to
    // swallow one, and never widen it past the run-to-run variance the gated runs show (none seen).
    mutationThreshold = 100

    // Incremental history at the plugin's default location under the git-ignored build directory: repeat local
    // runs re-mutate only changed code against the stored run. A fresh CI checkout has no prior history, so CI
    // always runs full-scope.
    enableDefaultIncrementalAnalysis = true

    // Explicit minion thread count, defaulting to a size a standard CI runner carries without oversubscribing;
    // a larger machine raises it with -PpitestThreads=N. PIT's +auto_threads is deliberately NOT used: its own
    // docs warn it misreads the core count on CI containers. Each minion boots the vanilla registries, but
    // memory is ample here, so the ceiling is processor count, not heap.
    threads.set(providers.gradleProperty("pitestThreads").map { it.toInt() }.orElse(4))
}

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
// Keeps cross-branch cherry-picks of core viable as era-bands accrue. A fail-closed ALLOWLIST like the
// pitest scope above, not a net.minecraft denylist: an MC-bundled library (com.mojang, gson, netty, joml,
// slf4j) compiles clean under both compileJava and the checkCoreJava8 floor compile (--release 8 accepts
// newer classfiles on the classpath), yet its presence and version vary per band, so it must not creep into
// core. A new core dependency is a deliberate amendment here, never an accident. It is a line-level import
// scan, wired into the check task.
// The lang fidelity check holds the translation issue form to the enrolled locale set, and that form sits
// above this module where no task consumes it. Without it declared here the test task is UP-TO-DATE on a
// form-only edit, so the drift the check exists to catch passes locally without the check ever running.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file(".github/ISSUE_TEMPLATE/5-translation.yml"))
        .withPropertyName("translationForm")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

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
    // The floor is enforced by --release 8, never by the compiler's own version, so this follows the band's
    // compile toolchain rather than its language target. A band that compiles on a newer JDK has only that JDK
    // installed, and asking here for the target's own JDK would demand an installation nothing provisions.

    // The floor is --release 8, not the compiler's own version, so this reuses the toolchain the project already
    // declares rather than naming a version a second time. A band overriding its compile toolchain is then
    // followed automatically and the two coordinates cannot drift apart.
    javaCompiler = javaToolchains.compilerFor(java.toolchain)
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
// CI installs java_build_version while Gradle resolves the compile toolchain, and nothing else makes the two
// agree: bump one without the other and local builds use a different JDK than CI does. The toolchain is
// java_version unless the band overrides it with java_toolchain_version (see wdl.java-conventions), so the
// comparison is against that, not against the language target.
val checkJavaVersion = tasks.register("checkJavaVersion") {
    group = "verification"
    description = "Fails if java_build_version's major disagrees with the compile toolchain"
    val toolchainVersion = providers.gradleProperty("java_toolchain_version").orNull
        ?: providers.gradleProperty("java_version").get()
    val buildVersion = providers.gradleProperty("java_build_version").get()
    inputs.property("toolchainVersion", toolchainVersion)
    inputs.property("buildVersion", buildVersion)
    doLast {
        // takeWhile, not substringBefore('.'), because setup-java accepts an early-access value like 25-ea
        // that has no dot to split on. A legacy 1.8.0_442 still reads as major 1 either way.
        val buildMajor = buildVersion.takeWhile { it.isDigit() }
        if (buildMajor != toolchainVersion) {
            throw GradleException(
                "java toolchain mismatch: java_build_version=$buildVersion (major $buildMajor) but the compile " +
                    "toolchain is $toolchainVersion; CI and Gradle would use different JDKs"
            )
        }
    }
}

// The band-divergence allowlist names the shared files that legitimately carry band-local code, checked by
// the branch-versus-dev propagation diff. That diff needs band branches to run, so this task enforces the one
// rule a path-pattern file cannot: every pattern carries a # reason, with text, on the line directly above it.
val checkBandDivergence = tasks.register("checkBandDivergence") {
    group = "verification"
    description = "Fails if a config/band-divergence.txt path pattern carries no reason comment above it"
    // Resolved at configuration time to a plain File, as the spotless config path above is, so doLast reads no
    // project accessor the configuration cache would reject.
    val registryFile = rootProject.file("config/band-divergence.txt")
    inputs.file(registryFile)
    doLast {
        val lines = registryFile.readLines()
        val offenders = lines.mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@mapIndexedNotNull null
            }
            val above = if (index > 0) lines[index - 1].trim() else ""
            // The reason must carry text, not a bare # that names nothing.
            if (above.startsWith("#") && above.drop(1).isNotBlank()) null else "  - line ${index + 1}: $line"
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "config/band-divergence.txt requires a # reason directly above every path pattern; missing for:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkCoreImports, checkPlugBand, checkJavaVersion, checkBandDivergence)
    // The Java-8 floor compile is moot on a band whose toolchain already is Java 8 (see checkCoreJava8).
    if (providers.gradleProperty("java_version").get().toInt() > 8) {
        dependsOn(checkCoreJava8)
    }
}

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

dependencies {
    // XaeroPlus public API for the overlay binding (compat/xaeroplus), compile-only (never a runtime require).
    // common is Mojmap here (NeoGradle vanilla), so it needs a Mojmapped flavor. XaeroPlus 2.35.1 publishes no
    // separate +neoforge-1.20.1 file at this band; its +forge-1.20.1 file declares both the forge and neoforge
    // loaders, and the binding uses only XaeroPlus's own API types, so it matches this classpath.
    compileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+forge-${property("minecraft_version")}")

    // JourneyMap public APIs for the overlay bindings, compile-only (never a runtime require). This band binds
    // both JourneyMap generations, each in its own binding: the 1.9 API (journeymap.client.api) for the 5.x
    // plugin in compat/journeymap, and the 2.0 API (journeymap.api.v2) for the 6.x plugin in compat/journeymap/v2.
    // The 1.20-1.9 stem (see gradle.properties) publishes no -neoforge-SNAPSHOT flavor, so common pulls the
    // loader-suffixless -SNAPSHOT flavor; both API surfaces are their own types, not Minecraft signatures.
    compileOnly("info.journeymap:journeymap-api:${property("journeymap_api_coordinate")}-SNAPSHOT")
    compileOnly("info.journeymap:journeymap-api-common:${property("journeymap_api_v2_coordinate")}")
}
