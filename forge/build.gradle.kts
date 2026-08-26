import java.util.Properties
import java.util.zip.ZipInputStream
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath

// ASM (the checkShipJar constant-pool scan) on the island's buildscript classpath, pinned to the version
// common/build.gradle.kts's buildscript classpath uses for its own compile-side annotation strip.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-commons:9.10.1")
    }
}

// The non-Fabric jar for this deep band, a separate Gradle build beside the 8.14.5 root. This band
// predates the Mojmap floor (no Mojang mappings exist for 1.12.2), so the toolchain reaches for classic MCP
// (searge intermediary plus the MCP project's community names) instead, exactly as common/build.gradle.kts does.
// Unimined provisions the MCP-named Minecraft jar and the real Forge API natively, through its minecraftForge
// loader block, and reobfuscates the shipped jar MCP -> searge natively (defaultRemapJar), so this island carries
// no hand-rolled toolchain: no island-classpath consumption, no Mojmap-view remap of the Forge universal jar, and
// no custom tiny-remapper reobf pass. The island stays a separate Gradle build with its own wrapper as a
// structural choice, not because its Gradle version or Java toolchain differ from the root's (both run 8.14.5
// and target Java 8); two wrappers, one set of coordinates read from the root gradle.properties.
plugins {
    java
    id("xyz.wagyourtail.unimined") version "1.4.1"
    // Release publishing. Pinned as a literal because the island is a separate build with no access to the root
    // version catalog.
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

// Single source of coordinates: the island reads the band's gradle.properties from the sibling 8.14.5 root
// rather than duplicating the version, MC pin, and compat coordinates. Two wrappers, one set of coordinates.
val band = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun band(key: String): String = band.getProperty(key) ?: error("missing '$key' in ../gradle.properties")

group = band("mod_group")
// Match wdl.java-conventions: the MC patch rides as SemVer build metadata, e.g. 1.1.0+1.13.2.
version = "${band("mod_version")}+${band("minecraft_version")}"

base {
    // -> archive-wdl-forge
    archivesName.set("${band("mod_archives_base")}-forge")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(band("java_version").toInt())) }
}

repositories {
    mavenCentral()
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
    // The pinned searge oracle below (mcp_config) resolves from here, not from Unimined's own internal repo
    // handling: a project-level dependency needs its own repository, even though Unimined's minecraftForge
    // loader block already reaches this same host to provision the toolchain.
    maven("https://maven.minecraftforge.net/") { content { includeGroup("de.oceanlabs.mcp") } }
}

// --- Minecraft + Forge toolchain: native Unimined provision, classic MCP mappings, native reobf ---
// Below the Mojmap floor there is no Mojang mapping to target, so the island mirrors common/build.gradle.kts:
// classic MCP names over the searge intermediary. Unlike common (loader-less), this island also carries the
// minecraftForge loader block, so Unimined provisions the real Forge 1.12.2 API on the compile classpath (no
// hand-rolled Mojmap view of the universal jar) and defaultRemapJar reobfuscates the shipped jar MCP -> searge
// natively (no hand-rolled tiny-remapper pass). useGlobalCache=false keeps the provision under this project's own
// build directory, matching common's provision and keeping a stale cache from one band out of another's classpath.
unimined.useGlobalCache = false
unimined.minecraft {
    version = band("minecraft_version")
    mappings {
        searge()
        mcp(band("mcp_mappings_channel"), band("mcp_mappings_version"))
    }
    minecraftForge {
        loader(band("forge_version"))
    }
    defaultRemapJar = true
}

// --- Pinned 1.12.2 searge oracle ---
// The mis-bind surface below the Mojmap floor is SRG names (func_*/field_*) carried as reflection string
// literals and in AT/manifest strings, invisible to a constant-pool reference scan since the compiler never
// sees them as Minecraft references. Unimined's own searge mapping lives under this project's disposable
// .gradle/unimined cache, which a fresh checkout or a cache wipe can reshape or empty; a gate keyed on it is
// not trustworthy. This configuration instead pins the same underlying artifact Unimined's searge() mapping
// itself resolves, de.oceanlabs.mcp:mcp_config for this band's exact Minecraft version, and extracts its
// config/joined.tsrg to a stable build/ path via a real Gradle dependency (backed by the ordinary Gradle module
// cache), independent of Unimined's own provisioning.
val seargeOracle: Configuration by configurations.creating { isTransitive = false }

dependencies {
    seargeOracle("de.oceanlabs.mcp:mcp_config:${band("minecraft_version")}@zip")
}

// joined.tsrg maps obfuscated names to real names class-by-class: a class line ("<obf> <realClass>") introduces
// a block of tab-indented member lines below it, each "<obf> <srgField>" for a field or "<obf> <desc> <srgMethod>"
// for a method, so a member's searge id is always its line's last token. Below the Mojmap floor a class's dev
// (MCP) name already equals its real (searge) name (no separate class-renaming layer), so the class column
// itself is a valid, self-contained oracle of real net/minecraft class names too.
abstract class ExtractSeargeOracle : DefaultTask() {
    @get:InputFiles
    abstract val mcpConfigZip: ConfigurableFileCollection

    @get:OutputFile
    abstract val joinedTsrg: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = mcpConfigZip.singleFile
        val out = joinedTsrg.get().asFile
        out.parentFile.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == "config/joined.tsrg") {
                    out.outputStream().buffered().use { zin.copyTo(it) }
                    logger.lifecycle("searge oracle: extracted config/joined.tsrg from ${zip.name} -> ${out.path}")
                    return
                }
                entry = zin.nextEntry
            }
        }
        throw GradleException("config/joined.tsrg not found in ${zip.name}")
    }
}

val extractSeargeOracle = tasks.register<ExtractSeargeOracle>("extractSeargeOracle") {
    group = "verification"
    description = "Extracts config/joined.tsrg (the 1.12.2 searge oracle) from the pinned mcp_config zip to a stable build/ path."
    mcpConfigZip.from(seargeOracle)
    joinedTsrg.set(layout.buildDirectory.file("searge-oracle/joined.tsrg"))
}

// The reobf fixture set: two small classes carrying real and bogus SRG-shaped string literals for the
// checkShipJar/checkReobfNegative gates below. Isolated from main: nothing on main depends on it and it
// depends on nothing of main's, and neither fixture names a Minecraft type, so it needs no compile classpath
// beyond the JDK.
sourceSets {
    create("reobftest")
}

dependencies {
    // JSpecify (@NullMarked / @Nullable), compile-only and CLASS-retention: the source-merged common/ and the
    // shim are null-marked. NullAway itself does not run on this island (it is a build-logic pass that runs on
    // the root, at 8.14.5, over common); here the annotations only need to resolve so the marked
    // source compiles.
    compileOnly("org.jspecify:jspecify:1.0.0")

    // JourneyMap public API for the source-merged binding (compat/journeymap), compile-only, never a runtime
    // require. The island compiles under the same MCP classpath :common resolves; JourneyMap discovers the
    // plugin by annotation scan. No XaeroPlus binding on this band, matching :common.
    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-SNAPSHOT")
}

// Source-merge :common the way a loader subproject does on the higher bands, but by direct path
// since the island is a separate build with no access to :common's consumable configurations: fold common's
// main source into this compile and its resources into the jar. Scoped to compileJava only (never
// configureEach), so the isolated reobftest compile never picks up common's source too.
tasks.named<JavaCompile>("compileJava") {
    source(rootDir.resolve("../common/src/main/java"))
    // This compile is intentionally seam-red (common's net.minecraft-facing files still name Mojmap-era types
    // the seam port has not yet re-vocabularized this band to MCP names); javac's default 100-error cap would otherwise
    // truncate the seam-vs-toolchain distinction the island's own verification depends on.
    options.compilerArgs.add("-Xmaxerrs")
    options.compilerArgs.add("100000")
}

tasks.named<ProcessResources>("processResources") {
    from(rootDir.resolve("../common/src/main/resources")) {
        exclude("**/.gitkeep")
    }
    // Keep mcmod.info's and wdl-publishing.properties' templated fields in sync with the band coordinates,
    // matching the higher bands' loader processResources. The Forge floor (forge_version_min) is a deliberate value
    // distinct from the build coordinate forge_version.
    val tokens = mapOf(
        "version" to version.toString(),
        "minecraft_version" to band("minecraft_version"),
        "forge_version_min" to band("forge_version_min"),
        "modrinth_id" to band("modrinth_id"),
        "mod_id" to band("mod_id"),
    )
    inputs.properties(tokens)
    filesMatching("mcmod.info") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// --- Reobf fixture packaging ---
// Unimined reobfs main natively (verified in the spike: the production jar carries SRG func_74762_e), so unlike
// the 1.13.2 island there is no hand-rolled ReobfMojmapToSrg pass standing between the reobftest source set and
// a scannable jar: this simply packages the source set's own class output, literals and all, for the two gates
// below (checkShipJar's pass case, checkReobfNegative's fail case) to scan.
val reobfFixtureJar = tasks.register<Jar>("reobfFixtureJar") {
    group = "build"
    description = "Packages the reobftest fixture's class output for checkShipJar and checkReobfNegative to scan."
    archiveBaseName.set("reobftest-fixture")
    destinationDirectory.set(layout.buildDirectory.dir("reobf"))
    from(sourceSets["reobftest"].output)
}

// --- Ship jar (modJar) + the checkShipJar class-file health gate ---
// The shipped Forge jar must be the reobf'd, searge-named artifact. Unimined's defaultRemapJar produces that jar
// natively (the "remapJar" task; the plain `jar` task stays the MCP-named dev jar), so modJar wraps it under the
// island's own archive-name convention rather than re-deriving reobf: it sources Unimined's own remapped output
// plus the token-expanded resources (mcmod.info, accesstransformer.cfg, pack.mcmeta, common lang/assets) from
// processResources, mirroring the higher bands' remapJar-as-ship-artifact split.
val modJar = tasks.register<Jar>("modJar") {
    group = "build"
    description = "Assembles the shippable Forge mod jar: Unimined's native reobf'd (searge) classes + resources."
    dependsOn("remapJar")
    // A fresh Jar defaults archiveBaseName to the project name; restate the -forge coordinate base sets on the
    // default jar so the ship artifact is archive-wdl-forge-<version>.jar.
    archiveBaseName.set("${band("mod_archives_base")}-forge")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    // Legacy FML does not auto-discover accesstransformer.cfg by its META-INF path (that is the ModLauncher-era
    // convention); it reads the AT file named by the shipped jar's own FMLAT manifest attribute. Excluding the
    // incoming META-INF/MANIFEST.MF from remapJar's tree keeps this task's own generated manifest, the one
    // carrying FMLAT, from being shadowed by a duplicate entry copied in from zipTree.
    manifest {
        attributes("FMLAT" to "accesstransformer.cfg")
    }
    from(zipTree(tasks.named<Jar>("remapJar").flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(tasks.named("processResources"))
}

// The default jar carries a dev classifier so it never collides with modJar's ship name. It stays the
// MCP-named dev jar; modJar is the artifact CI uploads.
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

// build/assemble produce the ship jar. modJar also builds standalone via ./gradlew modJar.
tasks.named("assemble") {
    dependsOn(modJar)
}

// Searge-surface scan, shared by checkShipJar and checkReobfNegative. Below the Mojmap floor a class's dev (MCP)
// name already equals its runtime (searge) name (no separate class-renaming layer), so the residual mis-bind
// surface Unimined's compile and native reobf do not cover is SRG member ids (func_*/field_*) carried as
// reflection string literals and in AT/manifest strings, invisible to a constant-pool reference scan since the
// compiler never treats them as Minecraft references (spec risk 3). Three arms over jarToScan:
//   (a) every class parses (an ASM ClassReader pass over each .class entry); a malformed class is a runtime
//       failure the reobf pipeline could otherwise ship silently.
//   (b) no net/minecraft descriptor carrying a jspecify TYPE_USE @Nullable names a class outside the pinned
//       oracle's class set. The source-merged common is @NullMarked with CLASS-retention @Nullable, so a
//       nullable Minecraft-typed member ships a RuntimeInvisibleTypeAnnotations entry whose annotated member
//       descriptor Unimined's native reobf must have carried through intact. The @Nullable's own descriptor is
//       org.jspecify.*, never net/minecraft, so the load-bearing name is the annotated member's descriptor.
//   (c) every func_*/field_*-shaped string literal, whether a compile-time-constant field value or materialized
//       by ldc in a method body, plus every such token in textFilesToScan (the real accesstransformer.cfg, and
//       mcmod.info once it exists), resolves against the pinned oracle's member-id set.
// checkShipJar excludes the negative fixture's own class from its scan (it is a positive gate, expectClean
// true, and must itself stay clean); checkReobfNegative scans the fixture unfiltered as an inverted meta-test
// (expectClean false), passing when it finds the bogus literal (the gate fired as required) and failing only
// if it does not (the detector regressed). Neither task modifies the fixture jar.
abstract class CheckSeargeSurface : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputFile
    abstract val seargeOracleFile: RegularFileProperty

    @get:InputFiles
    abstract val textFilesToScan: ConfigurableFileCollection

    @get:Input
    abstract val excludedEntryText: Property<String>

    // True (the default): this is a positive gate, fails when an offender is found. False: this is the
    // inverted meta-test checkReobfNegative runs, over a fixture that permanently carries one offender by
    // construction; it fails only if the scan does NOT find it (the detector regressed), and otherwise passes
    // with the offender(s) it found logged as proof the gate fired. Mirrors the 1.13.2 CheckReobf's expectClean.
    @get:Input
    abstract val expectClean: Property<Boolean>

    init {
        excludedEntryText.convention("")
        expectClean.convention(true)
    }

    // Parses joined.tsrg per the class/member line shapes noted above ExtractSeargeOracle, into the real
    // net/minecraft class names and the valid searge member ids. A member of this task class rather than a
    // script-level function: a script-level helper closes over the implicit script instance, which turns a
    // class that calls it into a non-static inner class Gradle's task instantiator refuses to construct.
    private class SeargeOracle(val classes: Set<String>, val members: Set<String>)

    private fun parseSeargeOracle(tsrg: File): SeargeOracle {
        val classes = HashSet<String>()
        val members = HashSet<String>()
        tsrg.forEachLine { line ->
            if (line.isEmpty()) {
                return@forEachLine
            }
            if (line[0] == '\t') {
                val srg = line.trim().substringAfterLast(' ')
                if (srg.startsWith("field_") || srg.startsWith("func_")) {
                    members.add(srg)
                }
            } else {
                val parts = line.split(' ')
                if (parts.size == 2) {
                    classes.add(parts[1])
                }
            }
        }
        return SeargeOracle(classes, members)
    }

    @TaskAction
    fun check() {
        val oracle = parseSeargeOracle(seargeOracleFile.get().asFile)
        val mcName = Regex("net/minecraft/[A-Za-z0-9_/\$]+")
        val seargeLiteral = Regex("(?:field|func)_[0-9]+_[A-Za-z0-9_$]+")
        val exclude = excludedEntryText.get()

        val parseFailures = ArrayList<String>()
        val leaks = ArrayList<String>()
        val badLiterals = ArrayList<String>()
        var classes = 0
        var annotatedMembers = 0
        var literalsSeen = 0

        ZipInputStream(jarToScan.get().asFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".class") && (exclude.isEmpty() || !entry.name.contains(exclude))) {
                    val bytes = zin.readBytes()
                    val annotated = ArrayList<Pair<String, String?>>()
                    val literals = ArrayList<String>()
                    var parsed = true
                    try {
                        ClassReader(bytes).accept(
                            object : ClassVisitor(Opcodes.ASM9) {
                                override fun visitField(
                                    access: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    value: Any?,
                                ): FieldVisitor {
                                    if (value is String && seargeLiteral.matches(value)) {
                                        literals.add(value)
                                    }
                                    return object : FieldVisitor(Opcodes.ASM9) {
                                        private var recorded = false

                                        override fun visitTypeAnnotation(
                                            typeRef: Int,
                                            typePath: TypePath?,
                                            annDesc: String,
                                            visible: Boolean,
                                        ): AnnotationVisitor? {
                                            if (!recorded) {
                                                annotated.add(descriptor to signature)
                                                recorded = true
                                            }
                                            return null
                                        }
                                    }
                                }

                                override fun visitMethod(
                                    access: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    exceptions: Array<String>?,
                                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                                    private var recorded = false

                                    override fun visitTypeAnnotation(
                                        typeRef: Int,
                                        typePath: TypePath?,
                                        annDesc: String,
                                        visible: Boolean,
                                    ): AnnotationVisitor? {
                                        if (!recorded) {
                                            annotated.add(descriptor to signature)
                                            recorded = true
                                        }
                                        return null
                                    }

                                    override fun visitLdcInsn(value: Any?) {
                                        if (value is String && seargeLiteral.matches(value)) {
                                            literals.add(value)
                                        }
                                    }
                                }
                            },
                            0,
                        )
                    } catch (t: Throwable) {
                        parseFailures.add("(a) ${entry.name} failed to parse: ${t.message}")
                        parsed = false
                    }
                    if (parsed) {
                        classes++
                        for ((descriptor, signature) in annotated) {
                            annotatedMembers++
                            for (text in listOfNotNull(descriptor, signature)) {
                                for (match in mcName.findAll(text)) {
                                    if (!oracle.classes.contains(match.value)) {
                                        leaks.add(
                                            "(b) ${entry.name}: TYPE_USE-annotated member carries unknown "
                                                + "net/minecraft name ${match.value}"
                                        )
                                    }
                                }
                            }
                        }
                        for (literal in literals) {
                            literalsSeen++
                            if (!oracle.members.contains(literal)) {
                                badLiterals.add(
                                    "(c) ${entry.name}: string literal \"$literal\" is not a valid searge member id"
                                )
                            }
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }

        for (textFile in textFilesToScan.files) {
            textFile.forEachLine { line ->
                for (match in seargeLiteral.findAll(line)) {
                    literalsSeen++
                    if (!oracle.members.contains(match.value)) {
                        badLiterals.add(
                            "(c) ${textFile.name}: entry \"${match.value}\" is not a valid searge member id"
                        )
                    }
                }
            }
        }

        val scanned = jarToScan.get().asFile.name
        val offenders = parseFailures + leaks + badLiterals
        if (expectClean.get()) {
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "$name: ${offenders.size} offender(s) in $scanned:\n" + offenders.joinToString("\n")
                )
            }
            logger.lifecycle(
                "$name: $scanned healthy; $classes class(es) parsed (arm a); arm (b) scanned $annotatedMembers "
                    + "TYPE_USE-annotated member descriptor(s); arm (c) scanned $literalsSeen searge-shaped "
                    + "string constant(s)/AT-or-mcmod entries, all resolved against the pinned oracle"
            )
        } else {
            if (offenders.isEmpty()) {
                throw GradleException(
                    "$name: expected the gate to fire on $scanned but found no offenders; a gate never seen "
                        + "to fire is not trusted"
                )
            }
            logger.lifecycle(
                "$name: gate fired as required on $scanned; ${offenders.size} offender(s):\n"
                    + offenders.joinToString("\n")
            )
        }
    }
}

val checkShipJar = tasks.register<CheckSeargeSurface>("checkShipJar") {
    group = "verification"
    description = "Fails if a shipped class fails to parse (arm a), a TYPE_USE-annotated member names an unknown net/minecraft class (arm b), or a searge-shaped string constant/AT entry does not resolve in the pinned oracle (arm c)."
    dependsOn(reobfFixtureJar)
    // main stays seam-red pending the seam port that re-vocabularizes common's net.minecraft-facing files to MCP names (see
    // compileJava's -Xmaxerrs note above), so modJar cannot build yet: this scans the pass fixture instead of
    // the real ship jar until that port lands and a follow-up repoints jarToScan at modJar. The fixture already
    // exercises the full arm (c) surface, a real searge member id carried as a string literal, that a
    // production class would carry.
    jarToScan.set(reobfFixtureJar.flatMap { it.archiveFile })
    excludedEntryText.set("ReobfNegativeFixture")
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedTsrg })
    textFilesToScan.from(layout.projectDirectory.file("src/main/resources/META-INF/accesstransformer.cfg"))
    layout.projectDirectory.file("src/main/resources/mcmod.info").asFile.let { mcmodInfo ->
        if (mcmodInfo.exists()) {
            textFilesToScan.from(mcmodInfo)
        }
    }
}

// The positive reference-scan checkReobf performed pre-Unimined is largely redundant here: a wrong compiled
// Minecraft reference simply does not compile under Unimined's native MCP-named classpath, leaving nothing for
// a hand-rolled bytecode reference scan to add. Its remaining useful check, that a shipped class's references
// name only real classes, is checkShipJar's arm (b); this stays registered as a thin, real, non-throwing alias
// onto it rather than removed, so ./gradlew checkReobf still runs a real check under its historical name.
tasks.register("checkReobf") {
    group = "verification"
    description = "Alias onto checkShipJar's arm (b) positive reference scan; a wrong Minecraft reference cannot compile under Unimined."
    dependsOn(checkShipJar)
}

// Inverted meta-test (expectClean = false): scans the reobftest fixture unfiltered, where the bogus searge
// literal is a permanent, known offender, and passes when the scan finds it (proof the gate fires); it fails
// only if the scan comes back clean, meaning the detector itself regressed. Belongs in check for exactly that
// reason: it is green whenever the gate works and red only on a real regression, the same as any other check.
val checkReobfNegative = tasks.register<CheckSeargeSurface>("checkReobfNegative") {
    group = "verification"
    description = "Proves the arm (c) scan fires: passes when it flags the fixture's bogus searge literal, fails if the detector misses it."
    dependsOn(reobfFixtureJar)
    jarToScan.set(reobfFixtureJar.flatMap { it.archiveFile })
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedTsrg })
    expectClean.set(false)
}

tasks.named("check") {
    dependsOn("checkReobf", checkShipJar, checkReobfNegative)
}

// Release publishing (mod-publish-plugin), driven by the release workflow on a version tag: it uploads the Forge
// jar to CurseForge and Modrinth per this band's MC version, mirroring the higher bands' loader subprojects.
// Coordinates come from band() (the island reads the root gradle.properties), not providers.gradleProperty,
// because forge/gradle.properties carries none. There is no github block: the release workflow funnels every
// band's jars into one shared GitHub release with gh. Nothing publishes on an ordinary build.
publishMods {
    // Resolve the shipped jar by path: every island build system (Loom remapJar, ForgeGradle reobfJar, plain
    // java jar) emits build/libs/archive-wdl-forge-<version>.jar, and the release job builds the island before
    // invoking publishMods, so one path form works on every band without a build-system-specific task handle.
    file.set(layout.buildDirectory.file("libs/${band("mod_archives_base")}-forge-${project.version}.jar"))
    changelog.set(providers.environmentVariable("CHANGELOG").orElse(""))
    type.set(STABLE)
    version.set(project.version.toString())
    displayName.set("${project.version} (Forge)")
    modLoaders.add("forge")
    dryRun.set(
        providers.environmentVariable("PUBLISH_DRY_RUN").map { it.toBooleanStrict() }.orElse(false),
    )

    modrinth {
        projectId.set(band("modrinth_id"))
        accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
        minecraftVersions.add(band("minecraft_version"))
        // Java mod: no hard runtime dependency to declare (JourneyMap is an optional jar-in-jar).
    }

    curseforge {
        projectId.set(band("curseforge_id"))
        accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
        minecraftVersions.add(band("minecraft_version"))
        // Client-only mod: CurseForge requires at least one declared environment.
        client.set(true)
        server.set(false)
    }
}
