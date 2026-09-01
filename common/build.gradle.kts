import net.ltgt.gradle.errorprone.errorprone

plugins {
    // common's Minecraft toolchain: loader-less Unimined against classic MCP mappings (see below). Versioned
    // inline, not catalog-pinned: common is the only consumer, and the forge/ island (a separate Gradle build)
    // pins its own copy independently.
    id("xyz.wagyourtail.unimined") version "1.4.1"
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

// --- Minecraft toolchain: loader-less Unimined against classic MCP mappings ---
// This band predates the Mojmap floor NeoForm/ModDevGradle and NeoGradle vanilla can target, and unlike the
// 1.13.2 parent it also predates Mojang's own official mappings (Mojang has published none before 1.14.4), so
// even a hand-composed bridge has nothing to bridge to. The toolchain reaches for the classic pre-Mojmap
// standard instead: searge (obf->SRG) composed with the MCP project's community names on top.
// Unimined resolves both natively from a single provisioned jar, with no Fabric/Legacy-Fabric intermediary hop
// and no hand-rolled bridge build. common is written in MCP names, so it compiles against Unimined's provisioned
// jar with no per-name glue; Unimined's test source set inherits the same jar, so the headless JUnit suite boots
// the vanilla registries against it.
val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val mcpMappingsChannel = providers.gradleProperty("mcp_mappings_channel").get()
val mcpMappingsVersion = providers.gradleProperty("mcp_mappings_version").get()
val forgeVersion = providers.gradleProperty("forge_version").get()

// useGlobalCache=false keeps every provisioned artifact under this project's own build directory rather than a
// shared user-home cache, matching genBridge's old per-project provisioning and keeping a stale cache from one
// band from ever leaking into another's compile classpath.
unimined.useGlobalCache = false
unimined.minecraft {
    version = minecraftVersion
    mappings {
        searge()
        mcp(mcpMappingsChannel, mcpMappingsVersion)
    }
    // Provision the same Forge view the island uses. This is a Forge-only band, and the ported pre-1.13 client
    // screens are Forge-view-dependent: Forge patches GuiScreen.mouseClicked/keyTyped/handleMouseInput to throw
    // IOException, which the loader-less vanilla view lacks, so a screen that overrides them and calls super cannot
    // compile against vanilla. Sharing the island's compile view (Forge-patched Minecraft over the MCP names) is
    // what lets :common compile the screens standalone for its gates and tests.
    minecraftForge {
        loader(forgeVersion)
    }
    // Forge-only band: common is built standalone for its gates and tests alone; it ships no jar of its own,
    // since the shippable artifact is the forge island's jar (the separate forge/ build, which compiles
    // common's source directly and reobfuscates natively through its own Unimined provision). common keeps
    // Unimined's MCP (dev) names and never reobfuscates, so defaultRemapJar stays off; only the island ships.
    defaultRemapJar = false
}

// --- bootsmoke: isolated boot-proof source set (./gradlew :common:bootSmoke) ---
// Proves the Unimined provision above actually runs under the Java 8 target. It reads main's COMPILE classpath
// for the Minecraft jar and libraries, never main's output, mirroring the forge island's reobftest isolation:
// nothing on main/test depends on bootsmoke, and bootsmoke depends on nothing of theirs.
val bootsmoke = sourceSets.create("bootsmoke")
val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "bootsmokeImplementation"(files(sourceSets.main.get().compileClasspath))
    "bootsmokeImplementation"(platform(libsCatalog.findLibrary("junit-bom").get()))
    "bootsmokeImplementation"("org.junit.jupiter:junit-jupiter")
    "bootsmokeCompileOnly"(libsCatalog.findLibrary("jspecify").get())
    "bootsmokeRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    // Gradle's own test source set has the identical gap bootsmoke works around above: Unimined wires its
    // provisioned Minecraft/Forge jar (plus every library it pulls in) onto main's own classpath only, never
    // test's, so compileTestJava and the runtime test classpath alike see none of it until this line. Sharing
    // main's already-resolved compile classpath, the same technique bootsmoke uses, needs no second Unimined
    // provision and stays byte-identical to the one jar main itself compiles against.
    testImplementation(files(sourceSets.main.get().compileClasspath))
}

tasks.register<Test>("bootSmoke") {
    group = "verification"
    description = "Boots the vanilla registries against the Unimined-provisioned MCP jar under the Java 8 target"
    testClassesDirs = bootsmoke.output.classesDirs
    classpath = bootsmoke.runtimeClasspath
    // package-info.class carries @NullMarked, whose own @Target lists the Java 9 ElementType.MODULE. Gradle's
    // JUnit Platform discovery scans every class file in testClassesDirs, including package-info, and a real
    // Java 8 runtime throws (not just warns, unlike javac under --release 8) reflecting on that annotation's
    // meta-annotation before JUnit's own class predicate gets to skip it as a non-test class.
    exclude("**/package-info.class")
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
// from Unimined's provisioned Minecraft jar), so registry-booting tests run in the minion exactly as under :test.
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
        "world.thearchive.wdl.adapter.ItemTreeWalk",
        "world.thearchive.wdl.adapter.NbtMerge",
        "world.thearchive.wdl.adapter.PlayerProgressSerializer",
        "world.thearchive.wdl.adapter.PlayerTag",
        "world.thearchive.wdl.adapter.RecoveredScan",
        "world.thearchive.wdl.adapter.SectionKey",
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
    // Every package under src/test (including the com.moulberry.flashback / de.johni0702.minecraft.bobby test
    // doubles) carries a package-info.class with @NullMarked, whose own @Target lists the Java 9
    // ElementType.MODULE. See bootSmoke's identical exclude above for why a Java 8 test runtime throws (not
    // just warns) reflecting on that annotation before JUnit's class predicate gets to skip it as a non-test
    // class. The main test task needs the same exclude for the same reason.
    exclude("**/package-info.class")
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
    val javaVersion = providers.gradleProperty("java_version").get().toInt()
    // Only the modern (Java 21) bands run this cross-check: on a Java-8-toolchain band compileJava already
    // builds core/ on Java 8, so the floor is enforced there directly, and javac 8 has no --release flag to run
    // this check with anyway (a local Int captured by value, so the onlyIf stays configuration-cache-safe).
    onlyIf { javaVersion > 8 }
    source = fileTree("src/main/java/world/thearchive/wdl/core") { include("**/*.java") }
    classpath = sourceSets["main"].compileClasspath
    destinationDirectory = layout.buildDirectory.dir("core-java8-check")
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
    // A plain floor compile: NullAway/Error Prone already ran on the real compileJava, so here only javac's
    // --release enforcement is wanted; leaving Error Prone on would run its default checks under --release 8.
    options.errorprone.enabled = false
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(javaVersion)
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
// CI installs java_build_version while the Gradle toolchain resolves java_version, and nothing else makes the
// two agree: bump one without the other and local builds use a different JDK than CI does.
val checkJavaVersion = tasks.register("checkJavaVersion") {
    group = "verification"
    description = "Fails if java_build_version's major disagrees with java_version"
    val javaVersion = providers.gradleProperty("java_version").get()
    val buildVersion = providers.gradleProperty("java_build_version").get()
    inputs.property("javaVersion", javaVersion)
    inputs.property("buildVersion", buildVersion)
    doLast {
        // takeWhile, not substringBefore('.'), because setup-java accepts an early-access value like 25-ea
        // that has no dot to split on. A legacy 1.8.0_442 still reads as major 1 either way.
        val buildMajor = buildVersion.takeWhile { it.isDigit() }
        if (buildMajor != javaVersion) {
            throw GradleException(
                "java toolchain mismatch: java_build_version=$buildVersion (major $buildMajor) but " +
                    "java_version=$javaVersion; CI and Gradle would use different JDKs"
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
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
    // Legacy Fabric intermediary (obf->intermediary) for this pre-1.14 band; the modern Fabric maven has none.
    maven("https://maven.legacyfabric.net/")
    // Unimined's own published artifacts, plus the classic searge/MCP mapping files it resolves against.
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://repo.spongepowered.org/maven")
    mavenCentral()
}

dependencies {
    // fastutil, compile-only. Minecraft carries fastutil at runtime on every band, but this band's cut is trimmed
    // and leaves out nine of the types the mod binds, seven of them used from core/, so without this the seam-free
    // core/ tree does not compile here. Nothing is bundled from this project: the Forge island packages the absent
    // classes into the ship jar and gates that they arrived, since only the island produces an artifact.
    compileOnly("it.unimi.dsi:fastutil:${property("fastutil_bundle_version")}")

    // JourneyMap 2.0 API for the overlay binding (compat/journeymap/v2), compile-only (never a runtime require;
    // JourneyMap provides it jar-in-jar). The -forge flavor carries classic MCP names, matching this build (see
    // gradle.properties); the Forge island reads the same key and pins the same flavor.
    compileOnly("info.journeymap:journeymap-api-forge:${property("journeymap_api_v2_coordinate")}")
}
