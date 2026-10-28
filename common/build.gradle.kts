import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.ltgt.gradle.errorprone.errorprone
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.10.1") }
}

plugins {
    alias(libs.plugins.loom)
    id("com.diffplug.spotless")
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("info.solidsoft.pitest") version "1.19.0"
}

// Spotless is applied here per-subproject rather than via the build-logic convention plugin: loaded from the included
// build, its Eclipse JDT formatter fails intermittently (InvocationTargetException) under the configuration cache +
// build cache + parallel execution, while from the root buildscript classpath it is stable.
spotless {
    java {
        target("src/**/*.java")
        eclipse("4.40").configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// This band predates the Mojmap floor NeoForm/ModDevGradle and NeoGradle vanilla can target (NeoForm has no pre-1.20.2
// release), so neither resolves official Mojang mappings for 1.13.2.
loom {
    // Legacy Fabric hosts intermediary for the pre-1.14 versions the modern Fabric maven does not.
    intermediaryUrl.set("https://maven.legacyfabric.net/net/legacyfabric/intermediary/%1\$s/intermediary-%1\$s-v2.jar")
}

// remapJar's Mojmap -> intermediary direction cannot even run: tiny-remapper hits unfixable conflicts (over Minecraft's
// own members, never WDL code), so leaving it wired into build would fail assemble for an artifact nobody uses.
tasks.matching { it.name == "remapJar" || it.name == "remapSourcesJar" }.configureEach {
    enabled = false
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // Loom resolves mappings(files(...)) at CONFIGURATION time, before any task can run, so no task edge in
    // this build can produce the jar in time. settings.gradle.kts runs genBridge during settings evaluation
    // instead, which is what actually puts the jar on disk before this line.
    mappings(
        files(rootProject.file("tools/mojmap-bridge/build/bridge/intermediary-mojmap.jar"))
    )
}

// Scope is a fail-closed ALLOWLIST, never a package glob. A glob would auto-enroll every future IO/session class the
// moment it lands.
pitest {
    pitestVersion = "1.30.0"
    junit5PluginVersion = "1.2.3" // required: the test platform is JUnit 5

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
        "world.thearchive.wdl.adapter.MerchantOfferScrub",
        "world.thearchive.wdl.adapter.NbtMerge",
        "world.thearchive.wdl.adapter.PlayerProgressSerializer",
        "world.thearchive.wdl.adapter.PlayerTag",
        "world.thearchive.wdl.adapter.RecoveredEntityContent",
        "world.thearchive.wdl.adapter.RecoveredScan",
        "world.thearchive.wdl.adapter.SectionKey",
        "world.thearchive.wdl.adapter.VanillaDimensions",
        "world.thearchive.wdl.adapter.impl.NaturalEquipment",
    ))

    // This is NOT the plugin default, which mirrors targetClasses. The *Test suffix is load-bearing, not decoration:
    // PIT loads every non-abstract class this glob matches to discover its test units, and a bare
    // world.thearchive.wdl.* also matches production classes. A production class that names a compile-only type (the
    // compat/ bindings name JourneyMap API types absent at test runtime) then throws NoClassDefFoundError during
    // discovery, which PIT counts as a non-green baseline and aborts the whole run. Anchoring on *Test keeps the
    // discovery set to actual test classes (all of ours end in Test; no production class does).
    targetTests.set(listOf("world.thearchive.wdl.*Test"))

    // All four method names are unique across the targetClasses, so the name globs hit only the intended methods. Never
    // widen this to swallow a real survivor; strengthen the covering test instead. floorSliceSize: the empty-hot-set
    // guard (hotCount <= 0) is equivalent under ConditionalsBoundary because the ceil-division fall-through already
    // yields 0 at hotCount == 0. mergeOne: the block-entity scan bound (i < size) is equivalent under
    // ConditionalsBoundary. schemeMismatch: the fail-soft catch's return-false mutant has no coverage. addDiscInto:
    // flipping either centerX + dx or centerZ + dz to a subtraction under MathMutator is equivalent, because dx and dz
    // each range over the symmetric interval from negative radius to radius and the inclusion test dx * dx + dz * dz <=
    // radiusSquared is unchanged by negating either offset, so the double loop assembles the identical final chunk set
    // either way.
    excludedMethods.set(listOf("floorSliceSize", "mergeOne", "schemeMismatch", "addDiscInto"))

    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports = false

    // Ratchet gate: fail the build on any survivor. A new survivor is triaged (killed, suppressed with a reason, or
    // rewritten away) rather than absorbed by lowering this; never lower it to swallow one, and never widen it past the
    // run-to-run variance the gated runs show.
    mutationThreshold = 100

    threads.set(providers.gradleProperty("pitestThreads").map { it.toInt() }.orElse(4))
}

// checkCoreImports is a fail-closed ALLOWLIST, not a net.minecraft denylist: an MC-bundled library (com.mojang, gson,
// netty, joml, slf4j) compiles clean under both compileJava and the checkCoreJava8 floor compile (--release 8 accepts
// newer classfiles on the classpath), yet its presence and version vary per band, so it must not creep into core. The
// lang fidelity check holds the translation issue form to the enrolled locale set, and that form sits above this module
// where no task consumes it. Without it declared here the test task is UP-TO-DATE on a form-only edit, so the drift the
// check exists to catch passes locally without the check ever running.
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
// core/ is cherry-picked byte-identical to the deep (<=1.16) bands, which run on Java 8, so it must use no Java 9+
// language feature (record, var, pattern instanceof, switch expression) or JDK API (List.of, Optional.isEmpty,
// Stream.toList). This fences the language level by recompiling core under --release 8, which the band toolchain would
// otherwise silently accept. --release (unlike source/target compatibility) restricts the visible JDK API, not just the
// bytecode version, so it catches API creep as well as syntax. Only enforced on the modern bands: a Java-8 band
// compiles core on JDK 8 natively (--release postdates JDK 8 so it cannot run).
val checkCoreJava8 = tasks.register<JavaCompile>("checkCoreJava8") {
    group = "verification"
    description = "Fails if core/ uses any construct unavailable on the Java 8 floor"
    source = fileTree("src/main/java/world/thearchive/wdl/core") { include("**/*.java") }
    classpath = sourceSets["main"].compileClasspath
    destinationDirectory = layout.buildDirectory.dir("core-java8-check")
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
    options.errorprone.enabled = false

    javaCompiler = javaToolchains.compilerFor(java.toolchain)
}
val checkPlugBand = tasks.register("checkPlugBand") {
    group = "verification"
    description = "Fails if the plug's BAND_FLOOR is above the targeted minecraft_version"
    // Two traps here: a top-level val is a script-object field the configuration cache cannot serialize (so keep this
    // local, captured by value in doLast), and bare property() would bind to the task receiver (Task.property cannot
    // see project properties), so go through providers.gradleProperty.
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
// CI installs java_build_version while Gradle resolves the compile toolchain, and nothing else makes the two agree. The
// toolchain is java_version unless the band overrides it with java_toolchain_version, so the comparison is against
// that, not against the language target.
val checkJavaVersion = tasks.register("checkJavaVersion") {
    group = "verification"
    description = "Fails if java_build_version's major disagrees with the compile toolchain"
    val toolchainVersion = providers.gradleProperty("java_toolchain_version").orNull
        ?: providers.gradleProperty("java_version").get()
    val buildVersion = providers.gradleProperty("java_build_version").get()
    inputs.property("toolchainVersion", toolchainVersion)
    inputs.property("buildVersion", buildVersion)
    doLast {
        // takeWhile, not substringBefore('.'), because setup-java accepts an early-access value like 25-ea that has no
        // dot to split on.
        val buildMajor = buildVersion.takeWhile { it.isDigit() }
        if (buildMajor != toolchainVersion) {
            throw GradleException(
                "java toolchain mismatch: java_build_version=$buildVersion (major $buildMajor) but the compile " +
                    "toolchain is $toolchainVersion; CI and Gradle would use different JDKs"
            )
        }
    }
}

// A path equal to the parent's is inherited and a path equal to dev's is not this band's divergence, so neither is
// drift; only a path differing from both must be registered.
val checkBandDivergence = tasks.register("checkBandDivergence") {
    group = "verification"
    description = "Fails if config/band-divergence.txt disagrees with the tree: a pattern without a reason, " +
        "an unregistered divergence from the mint parent, a stale pattern, or a core/ divergence"
    // Resolved at configuration time to plain Files, so doLast reads no project accessor the configuration cache would
    // reject.
    val registryFile = rootProject.file("config/band-divergence.txt")
    val repoDir = rootProject.layout.projectDirectory.asFile
    val mintParent = providers.gradleProperty("mint_parent").orNull?.trim().orEmpty()
    inputs.file(registryFile)
    inputs.property("mintParent", mintParent)
    doLast {
        val lines = registryFile.readLines()
        val unreasoned = lines.mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@mapIndexedNotNull null
            }
            val above = if (index > 0) lines[index - 1].trim() else ""
            // The reason must carry text, not a bare # that names nothing.
            if (above.startsWith("#") && above.drop(1).isNotBlank()) null else "  - line ${index + 1}: $line"
        }
        if (unreasoned.isNotEmpty()) {
            throw GradleException(
                "config/band-divergence.txt requires a # reason directly above every path pattern; missing for:\n" +
                    unreasoned.joinToString("\n")
            )
        }
        if (mintParent.isEmpty()) {
            logger.lifecycle(
                "checkBandDivergence: no mint_parent declared, so only the reason rule ran (a band names the branch " +
                    "it was minted from in gradle.properties; dev names none)"
            )
            return@doLast
        }

        // stdin comes from a file rather than a pipe so a long path list cannot deadlock against git's output.
        fun git(stdin: File?, vararg args: String): Pair<Int, String> {
            val builder = ProcessBuilder(listOf("git") + args).directory(repoDir)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            if (stdin != null) builder.redirectInput(stdin) else builder.redirectInput(ProcessBuilder.Redirect.PIPE)
            val process = builder.start()
            if (stdin == null) process.outputStream.close()
            val out = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return process.waitFor() to out
        }
        fun resolve(name: String): Pair<String, String> {
            for (ref in listOf("refs/heads/$name", "refs/remotes/origin/$name")) {
                val (code, out) = git(null, "rev-parse", "--verify", "-q", "$ref^{commit}")
                if (code == 0) return ref to out.trim()
            }
            throw GradleException(
                "checkBandDivergence: no ref named $name (neither refs/heads/$name nor refs/remotes/origin/$name); " +
                    "fetch it with: git fetch --no-tags --depth=1 origin +refs/heads/$name:refs/remotes/origin/$name"
            )
        }
        val excludedPaths = setOf("gradle.properties", "config/band-divergence.txt")
        val plugPrefix = "common/src/main/java/world/thearchive/wdl/adapter/impl/"
        val corePrefix = "common/src/main/java/world/thearchive/wdl/core/"
        fun isShared(path: String) = path !in excludedPaths && !path.startsWith(plugPrefix)
        // Renames are split (--no-renames) so each side registers on its own and the two-field parse holds.
        fun diffAgainst(sha: String): Map<String, String> {
            val (code, out) = git(null, "diff", "--no-renames", "--name-status", "-z", sha)
            if (code != 0) throw GradleException("checkBandDivergence: git diff against $sha exited $code")
            val fields = out.split('\u0000').filter { it.isNotEmpty() }
            return fields.chunked(2).filter { it.size == 2 && isShared(it[1]) }.associate { it[1] to it[0] }
        }

        val (parentRef, parentSha) = resolve(mintParent)
        val (devRef, devSha) = resolve("dev")
        val parentDiff = diffAgainst(parentSha)
        val devDiff = diffAgainst(devSha)
        val allPaths = (parentDiff.keys + devDiff.keys).sorted()

        // check-ignore -v -z prints source, line, pattern and path per input path, the first three empty for a
        // path nothing matches. Only a non-negated match sourced from the register counts; a .gitignore or
        // info/exclude match on the same path outranks the register and hides it, so such a path reads as drift
        // and its pattern as stale.
        val credit = mutableMapOf<String, Int>()
        if (allPaths.isNotEmpty()) {
            val pathList = File(temporaryDir, "paths").apply { writeText(allPaths.joinToString("\u0000")) }
            val (code, out) = git(
                pathList, "-c", "core.excludesFile=${registryFile.absolutePath}",
                "check-ignore", "--no-index", "-v", "-z", "--non-matching", "--stdin"
            )
            if (code != 0 && code != 1) throw GradleException("checkBandDivergence: git check-ignore exited $code")
            out.split('\u0000').chunked(4).filter { it.size == 4 }.forEach { (source, line, pattern, path) ->
                if (source == registryFile.absolutePath && !pattern.startsWith("!")) credit[path] = line.toInt()
            }
        }

        val drift = parentDiff.keys.filter { it in devDiff && it !in credit && !it.startsWith(corePrefix) }.sorted()
        val coreDrift = parentDiff.keys.filter { it.startsWith(corePrefix) }.sorted()
        val coreRegistered = credit.keys.filter { it.startsWith(corePrefix) }.sorted()
        val patternLines = lines.indices.filter { lines[it].trim().let { l -> l.isNotEmpty() && !l.startsWith("#") } }
            .map { it + 1 }
        val liveLines = credit.filterKeys { it in devDiff }.values.toSet()
        val stale = patternLines.filter { it !in liveLines }

        val report = StringBuilder()
        if (drift.isNotEmpty()) {
            report.append("  shared paths differing from both, with no pattern (register with a reason, or propagate the fix):\n")
            drift.forEach { report.append("    ${parentDiff[it]} $it\n") }
        }
        if (stale.isNotEmpty()) {
            report.append("  patterns no path differing from dev credits (drop the stale entry):\n")
            stale.forEach { report.append("    line $it: ${lines[it - 1].trim()}\n") }
        }
        if (coreDrift.isNotEmpty()) {
            report.append("  core/ main sources differing from the mint parent (a defect to fix, never to register):\n")
            coreDrift.forEach { report.append("    ${parentDiff[it]} $it\n") }
        }
        if (coreRegistered.isNotEmpty()) {
            report.append("  patterns credited by a core/ main source (core/ is never registered):\n")
            coreRegistered.forEach { report.append("    line ${credit[it]}: $it\n") }
        }
        val baselines = "mint parent $mintParent = $parentRef at ${parentSha.take(9)}, dev = $devRef at ${devSha.take(9)}"
        if (report.isNotEmpty()) {
            throw GradleException("config/band-divergence.txt disagrees with the tree ($baselines):\n$report")
        }
        logger.lifecycle(
            "checkBandDivergence: ${parentDiff.size} shared paths differ from $mintParent and ${devDiff.size} from dev, " +
                "every divergence registered and every pattern live ($baselines)"
        )
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
    maven("https://maven.legacyfabric.net/")
}

dependencies {
    compileOnly("info.journeymap:journeymap-api:${property("journeymap_api_coordinate")}-SNAPSHOT")
}

// Loom's merge stamps @Environment(EnvType.CLIENT) on every client class and on the client-only members of shared
// classes (and EnvironmentInterfaces on the two chest block entities), and no fabric-loader, which defines EnvType,
// sits on any classpath of this Forge-only band, so javac and javadoc each warn unknown enum constant once per class
// they read, up to the hundred-warning cap.
abstract class StripMinecraftAnnotations : DefaultTask() {
    private val fabricEnvironmentAnnotations = setOf(
        "Lnet/fabricmc/api/Environment;",
        "Lnet/fabricmc/api/EnvironmentInterface;",
        "Lnet/fabricmc/api/EnvironmentInterfaces;",
    )

    @get:InputFiles
    abstract val inputJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun strip() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        for (jar in inputJars.files) {
            val target = outDir.resolve(jar.nameWithoutExtension + "-stripped.jar")
            ZipInputStream(jar.inputStream().buffered()).use { zin ->
                ZipOutputStream(target.outputStream().buffered()).use { zout ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val bytes = zin.readBytes()
                        val transformed = if (entry.name.endsWith(".class")) stripClass(bytes) else bytes
                        zout.putNextEntry(ZipEntry(entry.name))
                        zout.write(transformed)
                        zout.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
            logger.lifecycle("stripped annotations from ${jar.name} -> ${target.name}")
        }
    }

    private fun stripClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
                if (descriptor in fabricEnvironmentAnnotations) null else super.visitAnnotation(descriptor, visible)

            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                val delegate = super.visitField(access, name, descriptor, signature, value) ?: return null
                return object : FieldVisitor(Opcodes.ASM9, delegate) {
                    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
                        if (descriptor in fabricEnvironmentAnnotations) null else super.visitAnnotation(descriptor, visible)
                }
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
                        if (descriptor in fabricEnvironmentAnnotations) null else super.visitAnnotation(descriptor, visible)

                    override fun visitAnnotableParameterCount(parameterCount: Int, visible: Boolean) {}

                    override fun visitParameterAnnotation(parameter: Int, descriptor: String?, visible: Boolean): AnnotationVisitor? = null
                }
            }
        }, 0)
        return writer.toByteArray()
    }
}

val strippedMinecraftDir = layout.buildDirectory.dir("stripped-minecraft").get().asFile

val stripMinecraftAnnotations = tasks.register<StripMinecraftAnnotations>("stripMinecraftAnnotations") {
    inputJars.from(sourceSets["main"].compileClasspath.filter { it.name.startsWith("minecraft-") })
    outputDir.set(strippedMinecraftDir)
}

val strippedMinecraft = fileTree(strippedMinecraftDir) { include("**/*.jar") }.builtBy(stripMinecraftAnnotations)

tasks.named<JavaCompile>("compileJava") {
    classpath = sourceSets["main"].compileClasspath.filter { !it.name.startsWith("minecraft-") } + strippedMinecraft
}
tasks.named<JavaCompile>("compileTestJava") {
    classpath = sourceSets["test"].compileClasspath.filter { !it.name.startsWith("minecraft-") } + strippedMinecraft
}
tasks.named<Javadoc>("javadoc") {
    classpath = sourceSets["main"].output + sourceSets["main"].compileClasspath.filter { !it.name.startsWith("minecraft-") } + strippedMinecraft
}

abstract class WriteIslandClasspath : DefaultTask() {
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:InputFiles
    abstract val stripped: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val entries = (libraries.files + stripped.files).map { it.absolutePath }
        outputFile.get().asFile.writeText(entries.joinToString("\n"))
    }
}

val writeIslandClasspath = tasks.register<WriteIslandClasspath>("writeIslandClasspath") {
    libraries.from(sourceSets["main"].compileClasspath.filter { !it.name.startsWith("minecraft-") })
    stripped.from(strippedMinecraft)
    outputFile.set(layout.buildDirectory.file("island-classpath.txt"))
}

// The island build reads island-classpath.txt from a separate, later invocation, so it only needs a producer
// edge somewhere in the root build's own lifecycle; assemble is what `./gradlew build` at the root runs.
tasks.named("assemble") {
    dependsOn(writeIslandClasspath)
}
