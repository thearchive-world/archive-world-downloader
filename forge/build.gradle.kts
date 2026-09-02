import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

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

// Mirrors wdl.java-conventions: the JDK that compiles is the language target unless the band overrides it
// through java_toolchain_version, and --release then pins this island's bytecode back to that target. band()
// errors on a missing key, so the optional coordinate is read off the properties directly.
val islandTarget = band("java_version").toInt()
val islandToolchain = band.getProperty("java_toolchain_version")?.toInt() ?: islandTarget

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(islandToolchain)) }
}

if (islandToolchain != islandTarget) {
    tasks.withType<JavaCompile>().configureEach { options.release.set(islandTarget) }
}

repositories {
    mavenCentral()
    // The band's own Minecraft library host, asked only for the trimmed fastutil cut this band's version manifest
    // names (see the fastutil bundling section below). It is the reference set the bundle subtracts against, so it
    // has to be the game's exact artifact rather than a same-version stand-in from Maven Central.
    maven("https://libraries.minecraft.net/") { content { includeModule("it.unimi.dsi", "fastutil") } }
    // The pinned searge oracle below (the mcp srg zip) resolves from here, not from Unimined's own internal repo
    // handling: a project-level dependency needs its own repository, even though Unimined's minecraftForge
    // loader block already reaches this same host to provision the toolchain.
    maven("https://maven.minecraftforge.net/") { content { includeGroup("de.oceanlabs.mcp") } }
}

// --- Minecraft + Forge toolchain: native Unimined provision, classic MCP mappings, native reobf ---
// Below the Mojmap floor there is no Mojang mapping to target, so the island mirrors common/build.gradle.kts:
// classic MCP names over the searge intermediary. Unlike common (loader-less), this island also carries the
// minecraftForge loader block, so Unimined provisions the real Forge API on the compile classpath (no
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

// --- Pinned 1.11.2 searge oracle ---
// The mis-bind surface below the Mojmap floor is SRG names (func_*/field_*) carried as reflection string
// literals and in AT/manifest strings, invisible to a constant-pool reference scan since the compiler never
// sees them as Minecraft references. Unimined's own searge mapping lives under this project's disposable
// .gradle/unimined cache, which a fresh checkout or a cache wipe can reshape or empty; a gate keyed on it is
// not trustworthy. This configuration instead pins the same underlying artifact Unimined's searge() mapping
// itself resolves and extracts it to a stable build/ path via a real Gradle dependency (backed by the ordinary
// Gradle module cache), independent of Unimined's own provisioning.
//
// The artifact is de.oceanlabs.mcp:mcp:<mc>:srg, not the parent band's de.oceanlabs.mcp:mcp_config:<mc>.
// mcp_config has no 1.11.x publication at all: sorted ascending its oldest release is 1.12.2, so the parent band
// sits exactly on that floor and every band below it takes the older mcp:<mc>:srg line instead. That is a format
// change as well as a coordinate change, from TSRG to SRG v1; see the grammar note above ExtractSeargeOracle.
val seargeOracle: Configuration by configurations.creating { isTransitive = false }

// This band's joined.srg parses to exactly these counts under a correct SRG v1 reader: 3122 CL: records, and
// 18826 distinct searge member ids (9941 field_* and 8885 func_*). The member figure is corroborated
// independently by the mapping-coverage measurement recorded in ../gradle.properties, which counts the same
// 8885 methods and 9941 fields from the 32-1.11 MCP export. Both check tasks assert them; see the note on
// CheckSeargeSurface.expectedOracleClasses for why an unasserted parse is the silent failure here.
val seargeOracleClasses = 3122
val seargeOracleMembers = 18826

dependencies {
    seargeOracle("de.oceanlabs.mcp:mcp:${band("minecraft_version")}:srg@zip")
}

// joined.srg maps obfuscated names to real names one self-contained line at a time, in SRG v1: every line carries
// its own record type as a prefix and there is no indentation and no scoping anywhere in the file, unlike the
// TSRG the 1.12.2-and-above bands read, where a class line opens a block of tab-indented member lines beneath it.
// The four record types and their columns:
//
//   PK: <obfPackage> <realPackage>
//   CL: <obfClass> <realClass>
//   FD: <obfOwner>/<obfField> <realOwner>/<seargeField>
//   MD: <obfOwner>/<obfMethod> <obfDescriptor> <realOwner>/<seargeMethod> <realDescriptor>
//
// Two column facts decide whether a reader is right, and getting either wrong yields exactly zero ids rather than
// a loud failure. A member name is fully qualified, so the searge id is the last SLASH-separated segment of its
// target column, never the whole token. And on an MD: line the target column is field 4, not the line's last
// token, which is the trailing descriptor; a last-token parse over this file returns no searge ids at all.
// Keying a record on its token count instead of its prefix is the other trap: PK: and FD: lines both carry three
// tokens, so an arity test sweeps package names into the class set.
//
// Below the Mojmap floor a class's dev (MCP) name already equals its real (searge) name (no separate
// class-renaming layer), so the CL: target column is a valid, self-contained oracle of real net/minecraft class
// names too. Inner classes carry $ verbatim there and must stay unnormalized, since arm (b) matches the $ form.
abstract class ExtractSeargeOracle : DefaultTask() {
    @get:InputFiles
    abstract val seargeZip: ConfigurableFileCollection

    @get:OutputFile
    abstract val joinedSrg: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = seargeZip.singleFile
        val out = joinedSrg.get().asFile
        out.parentFile.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == "joined.srg") {
                    out.outputStream().buffered().use { zin.copyTo(it) }
                    logger.lifecycle("searge oracle: extracted joined.srg from ${zip.name} -> ${out.path}")
                    return
                }
                entry = zin.nextEntry
            }
        }
        throw GradleException("joined.srg not found in ${zip.name}")
    }
}

val extractSeargeOracle = tasks.register<ExtractSeargeOracle>("extractSeargeOracle") {
    group = "verification"
    description = "Extracts joined.srg (the 1.11.2 searge oracle) from the pinned mcp srg zip to a stable build/ path."
    seargeZip.from(seargeOracle)
    joinedSrg.set(layout.buildDirectory.file("searge-oracle/joined.srg"))
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

    // JourneyMap 1.x API for the source-merged binding (compat/journeymap), compile-only, never a runtime require
    // (JourneyMap provides it itself). The build serving this band bundles the 1.x journeymap.client.api surface
    // and no journeymap.api.v2, and the jar names its Minecraft types in classic MCP, which is what this island
    // compiles in. It resolves from Maven Central rather than from blamejared, which publishes only the 2.0 line
    // and nothing on 1.11.x. JourneyMap discovers the plugin by annotation scan. No XaeroPlus binding on this
    // band, matching :common.
    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}")

    // fastutil, compile-only and mirroring :common's own pin: this compile source-merges common, whose core/
    // subtree binds types this band's trimmed Minecraft fastutil leaves out. Compile-only because the classes
    // that are actually missing are packaged into the ship jar by bundleFastutil below rather than pulled in
    // wholesale; the game supplies the rest at runtime.
    compileOnly("it.unimi.dsi:fastutil:${band("fastutil_bundle_version")}")
}

// Source-merge :common the way a loader subproject does on the higher bands, but by direct path
// since the island is a separate build with no access to :common's consumable configurations: fold common's
// main source into this compile and its resources into the jar. Scoped to compileJava only (never
// configureEach), so the isolated reobftest compile never picks up common's source too.
tasks.named<JavaCompile>("compileJava") {
    source(rootDir.resolve("../common/src/main/java"))
    // This compile source-merges the whole of common, so a red run here produces errors by the file rather
    // than by the line, and javac prints only the first hundred of them. Raising the cap is what keeps the
    // list readable; it is inert on a green compile rather than spent.
    options.compilerArgs.add("-Xmaxerrs")
    options.compilerArgs.add("100000")
}

tasks.named<ProcessResources>("processResources") {
    from(rootDir.resolve("../common/src/main/resources")) {
        exclude("**/.gitkeep")
    }
    // Keep mcmod.info's and wdl-publishing.properties' templated fields in sync with the band coordinates,
    // matching the higher bands' loader processResources. Only the tokens those two files actually name are listed:
    // the Forge runtime floor rides WdlForge's own @Mod annotation at this band rather than a manifest field, so
    // there is nothing here to expand it into and checkForgeFloor gates it against forge_version_min instead.
    val tokens = mapOf(
        "version" to version.toString(),
        "modrinth_id" to band("modrinth_id"),
        "mod_id" to band("mod_id"),
    )
    inputs.properties(tokens)
    filesMatching("mcmod.info") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// --- Lang catalog conversion (JSON -> .lang) ---
// MC 1.12.2 loads .lang (key=value), the format Mojang replaced at 1.13. The JSON catalog under
// common/src/main/resources/assets/wdl/lang stays the source of truth (LangFidelityTest/LangKeyCoverageTest/etc.
// keep reading it, unaffected by this task); this task converts a copy to .lang at package time so the shipped
// jar carries the format 1.12.2 expects. Uses Gradle's bundled groovy.json.JsonSlurper, no new buildscript
// dependency; keys are sorted for byte-reproducibility.
abstract class ConvertLangToProperties : DefaultTask() {
    @get:InputDirectory abstract val jsonDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction fun convert() {
        val out = outDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        jsonDir.get().asFile.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { json ->
            @Suppress("UNCHECKED_CAST")
            val map = groovy.json.JsonSlurper().parse(json) as Map<String, Any?>
            val lang = out.resolve(json.nameWithoutExtension + ".lang")
            lang.bufferedWriter(Charsets.UTF_8).use { w ->
                map.toSortedMap().forEach { (k, v) ->
                    w.write("$k=${v.toString().replace("\n", " ")}"); w.write("\n")
                }
            }
        }
    }
}

val convertLang = tasks.register<ConvertLangToProperties>("convertLang") {
    group = "build"
    description = "Converts the JSON lang catalog to 1.12.2 .lang (key=value) for the ship jar."
    jsonDir.set(layout.projectDirectory.dir("../common/src/main/resources/assets/wdl/lang"))
    outDir.set(layout.buildDirectory.dir("generated-lang/assets/wdl/lang"))
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

// Unimined's remapJar otherwise defaults to the same archiveBaseName/version as modJar below (no classifier),
// which collides with modJar's own ship-name output path: modJar's from(zipTree(remapJar.archiveFile)) would
// then open its own output file for writing before reading it back, truncating the very jar it is trying to
// read. Classifying remapJar's output "searge" gives modJar a distinct file to read from. It also leaves
// build/libs, because both workflows select this island's jars by globbing that directory and skipping the
// classifiers they know about: an intermediate sitting beside modJar's output is published as if it were the mod.
tasks.named<Jar>("remapJar") {
    archiveClassifier.set("searge")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

// --- fastutil bundling ---
// Minecraft stops carrying the fastutil classes core/ binds, and this band is where that starts. 1.12.2 and every
// band above it ship a full fastutil on the game's own classpath (7.1.0 there, 8.5.x on the modern bands) and the
// mod has always relied on that, declaring the dependency nowhere. 1.11.2 ships it.unimi.dsi:fastutil:7.0.12_mojang
// instead, a 355-class trimmed cut whose only primitive sets are the linear-scanning LongArraySet and IntArraySet
// and whose only long-keyed maps are Long2Object; nine of the twenty fastutil types the mod binds are absent from
// it, seven of those nine used from core/.
//
// Rewriting core/ to the trimmed surface was measured and rejected. It would mean either O(n) membership scans
// over thousands of chunks or a Long2ObjectOpenHashMap holding boxed values, and it would fork core/ (byte-
// identical across every branch by construction) permanently across this band and the four below it. The absent
// classes ride in the ship jar instead, unrelocated, at their own package path.
//
// Unrelocated is only safe because nothing is duplicated. fastutilShipped below resolves the exact artifact this
// band's own Minecraft version manifest names, and only classes ABSENT from it are packaged, so no class in the
// ship jar shadows one the game already loads and no load order has to be reasoned about. The two are the same
// fastutil release, Mojang's a trimmed recompile of 7.0.12, so a bundled class links correctly against the
// game's own copies of its supertypes.
//
// The bundled set is enumerated mechanically rather than hand-listed. The roots are whatever fastutil types the
// shipped source imports, and the closure is walked over the full jar's own bytecode. A hand-curated absent list
// would go stale the first time core/ binds another type, and on this project such a list has been found
// incomplete twice.
val fastutilBundleSource: Configuration by configurations.creating { isTransitive = false }
val fastutilShipped: Configuration by configurations.creating { isTransitive = false }

dependencies {
    fastutilBundleSource("it.unimi.dsi:fastutil:${band("fastutil_bundle_version")}@jar")
    fastutilShipped("it.unimi.dsi:fastutil:${band("fastutil_shipped_version")}@jar")
}

// Walks the fastutil classes the shipped source imports, plus everything their bytecode reaches, and writes out
// only those the band's Minecraft does not already carry. Shared by the packaging step and by the gate that reads
// the finished jar, which is why the root and closure logic lives in one place.
abstract class FastutilClosure : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTrees: ConfigurableFileCollection

    @get:InputFiles
    abstract val bundleSourceJar: ConfigurableFileCollection

    @get:InputFiles
    abstract val shippedJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    companion object {
        const val PACKAGE_PREFIX = "it/unimi/dsi/fastutil/"
        private val IMPORT = Regex("""^\s*import\s+(it\.unimi\.dsi\.fastutil\.[A-Za-z0-9_.]+)\s*;""")

        // A source import is dotted all the way down, so a nested type reads the same as a top-level one. Resolve
        // it by trying the plain form first and then folding trailing dots into '$' until an entry matches.
        fun resolveImport(imported: String, present: Set<String>): String? {
            var candidate = imported.replace('.', '/')
            while (true) {
                if (present.contains("$candidate.class")) {
                    return candidate
                }
                val lastSlash = candidate.lastIndexOf('/')
                if (lastSlash < 0) {
                    return null
                }
                candidate = candidate.substring(0, lastSlash) + "$" + candidate.substring(lastSlash + 1)
            }
        }

        fun importedTypes(files: Iterable<File>): Set<String> {
            val imports = sortedSetOf<String>()
            for (file in files) {
                if (file.isFile && file.name.endsWith(".java")) {
                    file.forEachLine { line -> IMPORT.find(line)?.let { imports.add(it.groupValues[1]) } }
                }
            }
            return imports
        }

        // Every internal name ASM sees while visiting the class, which is what a Remapper is asked to map:
        // supertypes, field and method descriptors, generic signatures, instruction owners, LDC class constants
        // and the InnerClasses attribute all pass through it.
        fun referencedTypes(bytes: ByteArray): Set<String> {
            val referenced = HashSet<String>()
            val collector = object : Remapper() {
                override fun map(internalName: String): String {
                    referenced.add(internalName)
                    return internalName
                }
            }
            ClassReader(bytes).accept(ClassRemapper(object : ClassVisitor(Opcodes.ASM9) {}, collector), 0)
            return referenced
        }
    }

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        ZipFile(shippedJar.singleFile).use { shipped ->
            ZipFile(bundleSourceJar.singleFile).use { full ->
                val shippedNames = shipped.entries().toList().map { it.name }.toSet()
                val fullNames = full.entries().toList().map { it.name }.toSet()

                // A type plus its nested siblings, since a nested class is a separate entry with its own name.
                fun entriesFor(internalName: String): List<String> =
                    fullNames.filter { it == "$internalName.class" || it.startsWith("$internalName$") && it.endsWith(".class") }

                val imports = importedTypes(sourceTrees.asFileTree.files)
                val roots = ArrayList<String>()
                for (imported in imports) {
                    val resolved = resolveImport(imported, fullNames)
                        ?: throw GradleException(
                            "$name: the shipped source imports $imported but ${bundleSourceJar.singleFile.name} "
                                + "carries no such class, so the bundle cannot be completed from it"
                        )
                    if (!shippedNames.contains("$resolved.class")) {
                        roots.add(resolved)
                    }
                }

                val bundled = sortedSetOf<String>()
                val queue = ArrayDeque<String>()
                roots.forEach { queue.addAll(entriesFor(it)) }
                while (queue.isNotEmpty()) {
                    val entryName = queue.removeFirst()
                    if (!bundled.add(entryName)) {
                        continue
                    }
                    val bytes = full.getInputStream(full.getEntry(entryName)).use { it.readBytes() }
                    for (reference in referencedTypes(bytes)) {
                        if (!reference.startsWith(PACKAGE_PREFIX)) {
                            continue
                        }
                        for (candidate in entriesFor(reference)) {
                            if (!shippedNames.contains(candidate) && !bundled.contains(candidate)) {
                                queue.add(candidate)
                            }
                        }
                    }
                }

                var bytesWritten = 0L
                for (entryName in bundled) {
                    val target = out.resolve(entryName)
                    target.parentFile.mkdirs()
                    val bytes = full.getInputStream(full.getEntry(entryName)).use { it.readBytes() }
                    target.writeBytes(bytes)
                    bytesWritten += bytes.size
                }
                logger.lifecycle(
                    "$name: ${imports.size} fastutil type(s) imported by the shipped source, ${roots.size} of them "
                        + "absent from ${shippedJar.singleFile.name}; bundled ${bundled.size} class file(s) "
                        + "($bytesWritten bytes) from ${bundleSourceJar.singleFile.name}"
                )
            }
        }
    }
}

val bundleFastutil = tasks.register<FastutilClosure>("bundleFastutil") {
    group = "build"
    description = "Extracts the fastutil classes the shipped source needs and this band's Minecraft does not carry."
    sourceTrees.from(rootDir.resolve("../common/src/main/java"), layout.projectDirectory.dir("src/main/java"))
    bundleSourceJar.from(fastutilBundleSource)
    shippedJar.from(fastutilShipped)
    outputDir.set(layout.buildDirectory.dir("bundled-fastutil"))
}

// --- Ship jar (modJar) + the checkShipJar class-file health gate ---
// The shipped Forge jar must be the reobf'd, searge-named artifact. Unimined's defaultRemapJar produces that jar
// natively (the "remapJar" task; the plain `jar` task stays the MCP-named dev jar), so modJar wraps it under the
// island's own archive-name convention rather than re-deriving reobf: it sources Unimined's own remapped output,
// which already carries the token-expanded resources (mcmod.info, accesstransformer.cfg, pack.mcmeta, common
// assets) since remapJar repackages the "jar" task's own sourceSet output; a direct from(processResources) would
// only re-add the identical bytes a second time. The one resource this island packages differently from
// processResources' own output is the lang directory: MC 1.12.2 loads .lang, not the JSON Mojang adopted at
// 1.13, so the source lang JSON is excluded here and convertLang's converted .lang output takes its place.
// Ship the license inside the jar: GPL-3.0 section 4, which LGPL-3.0 section 0 incorporates, asks that every
// recipient get a copy of the License with the Program, and a mod jar travels on its own far from any listing page
// (modpacks, mirrors, a copied mods/ folder). The modern bands package it in wdl.common-merge, which this band
// does not carry, and the island packages no license of its own, so modJar carries it. Captured into a val
// of plain Files, resolved at configuration time, so no Project reference survives into task execution
// (configuration cache), mirroring how the island already reads ../gradle.properties and ../common.
val licenseTexts = listOf(rootDir.resolve("../LICENSE"), rootDir.resolve("../GPL-3.0.txt"))

val modJar = tasks.register<Jar>("modJar") {
    group = "build"
    description = "Assembles the shippable Forge mod jar: Unimined's native reobf'd (searge) classes + resources."
    dependsOn("remapJar")
    dependsOn(convertLang)
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
        exclude("assets/wdl/lang/*.json")
    }
    from(convertLang.map { it.outputs.files.singleFile }) {
        into("assets/wdl/lang")
    }
    from(licenseTexts) { into("META-INF") }
    // The fastutil classes this band's Minecraft leaves out, at their own package path and unrelocated (see
    // bundleFastutil). They come from a plain directory rather than through remapJar's tree: they are third-party
    // bytecode with no Minecraft references, so reobfuscating them would be wrong as well as pointless.
    from(bundleFastutil)
    // fastutil is Apache 2.0 and ships no license file of its own, so section 4(a)'s copy travels with the jar
    // beside WDL's own two. Renamed on the way in to name the dependency it covers, since three license files in
    // one META-INF otherwise say nothing about which applies to what.
    from(rootDir.resolve("../APACHE-2.0.txt")) {
        into("META-INF")
        rename { "APACHE-2.0-fastutil.txt" }
    }
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

    // The oracle's pinned parse yield, asserted on every parse. A wrong SRG v1 reader is the one failure in this
    // area that no arm of the scan can report: an under-permissive reader (the TSRG parser left in place, say)
    // yields an empty oracle, which makes arm (b) and arm (c) flag everything and reads as ordinary seam fallout,
    // while an OVER-permissive one (harvesting the obfuscated column, or every token on the line) yields a
    // populated but wrong member set that admits bad literals and passes green. checkReobfNegative cannot
    // distinguish either case, since it demands an offender and a broken oracle manufactures one. Asserting the
    // exact counts catches both directions, and it is checked here rather than in a gate of its own so that every
    // consumer of the oracle validates it, including the inverted meta-test.
    @get:Input
    abstract val expectedOracleClasses: Property<Int>

    @get:Input
    abstract val expectedOracleMembers: Property<Int>

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

    // Parses joined.srg per the SRG v1 record shapes noted above ExtractSeargeOracle, into the real
    // net/minecraft class names and the valid searge member ids. A member of this task class rather than a
    // script-level function: a script-level helper closes over the implicit script instance, which turns a
    // class that calls it into a non-static inner class Gradle's task instantiator refuses to construct.
    private class SeargeOracle(val classes: Set<String>, val members: Set<String>)

    private fun parseSeargeOracle(srg: File): SeargeOracle {
        val classes = HashSet<String>()
        val members = HashSet<String>()
        // Records are keyed on the line's prefix, never on its token count: PK: and FD: lines both carry three
        // tokens. Only field_*/func_* names are kept, since FD: also names enum constants and synthetics verbatim.
        fun addMember(qualified: String) {
            val id = qualified.substringAfterLast('/')
            if (id.startsWith("field_") || id.startsWith("func_")) {
                members.add(id)
            }
        }
        srg.forEachLine { line ->
            val parts = line.split(' ')
            when (parts[0]) {
                "CL:" -> if (parts.size >= 3) classes.add(parts[2])
                "FD:" -> if (parts.size >= 3) addMember(parts[2])
                "MD:" -> if (parts.size >= 5) addMember(parts[3])
            }
        }
        val expectedClasses = expectedOracleClasses.get()
        val expectedMembers = expectedOracleMembers.get()
        if (classes.size != expectedClasses || members.size != expectedMembers) {
            throw GradleException(
                "$name: the searge oracle parsed to ${classes.size} class(es) and ${members.size} member id(s), "
                    + "but ${srg.name} for this band pins $expectedClasses and $expectedMembers. Too few means "
                    + "the reader is not reading SRG v1 at all; too many means it is harvesting the obfuscated "
                    + "column or the trailing descriptor, which would let a wrong searge literal ship green."
            )
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
    dependsOn(modJar)
    // common is re-vocabularized to MCP names and modJar builds, so this scans the real reobf'd ship jar rather
    // than the reobftest fixture it read while the seam port was in flight: every shipped class parses (arm a), no
    // TYPE_USE-annotated member names a net/minecraft class outside the pinned oracle (arm b), and every
    // searge-shaped string literal plus every accesstransformer.cfg and mcmod.info entry resolves against the
    // oracle (arm c). checkReobfNegative keeps the fixture as the inverted meta-test that proves the arm (c) scan
    // still fires, so no excludedEntryText is needed here: the real ship jar carries no negative fixture class.
    jarToScan.set(modJar.flatMap { it.archiveFile })
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedSrg })
    expectedOracleClasses.set(seargeOracleClasses)
    expectedOracleMembers.set(seargeOracleMembers)
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
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedSrg })
    expectedOracleClasses.set(seargeOracleClasses)
    expectedOracleMembers.set(seargeOracleMembers)
    expectClean.set(false)
}

// The Forge runtime floor is written twice and no compiler sees both: forge_version_min in ../gradle.properties is
// the band coordinate, and WdlForge's @Mod(dependencies = ...) is the string legacy FML actually enforces
// (FMLModContainer.bindMetadata takes dependencies off the annotation descriptor unless mcmod.info opts into
// useDependencyInformation, which this mod does not). An annotation value must be a compile-time constant, so the
// literal cannot read the property and the duplication is structural. Without this gate a bumped property changes
// nothing the loader sees, silently.
val checkForgeFloor = tasks.register("checkForgeFloor") {
    group = "verification"
    description = "Fails if WdlForge's @Mod dependencies floor does not match forge_version_min"
    // Captured by value at configuration time, like checkPlugBand's own locals, so no Project reference survives
    // into task execution.
    val forgeVersionMin = band("forge_version_min")
    val entrypointSource = layout.projectDirectory
        .file("src/main/java/world/thearchive/wdl/forge/WdlForge.java")
    inputs.property("forgeVersionMin", forgeVersionMin)
    inputs.file(entrypointSource)
    doLast {
        val declared = Regex("""required-after:forge@\[([^,\]]+),""")
            .find(entrypointSource.asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException(
                "WdlForge declares no required-after:forge floor in its @Mod dependencies; legacy FML then "
                    + "enforces no Forge version at all"
            )
        if (declared != forgeVersionMin) {
            throw GradleException(
                "Forge floor mismatch: forge_version_min is $forgeVersionMin but WdlForge's @Mod dependencies "
                    + "declares $declared. The annotation is the one legacy FML enforces, so change both."
            )
        }
    }
}

// The band's Minecraft version is written twice and no compiler sees both: minecraft_version in
// ../gradle.properties is the coordinate the whole toolchain provisions against, and WdlForge's
// @Mod(acceptedMinecraftVersions = ...) is the string legacy FML actually enforces (FMLModContainer.bindMetadata
// takes it off the annotation descriptor unconditionally; mcmod.info carries no mcversion field in this FML
// release, so there is nothing to cross-check it against). An annotation value must be a compile-time constant,
// so the literal cannot read the property and the duplication is structural. Nothing else covers it: the
// searge scan reads only func_*/field_*-shaped tokens, and a mod re-pinned to a new band while this literal
// names the old one compiles, packages and passes every other gate, then fails to load at all in a real client.
val checkAcceptedMinecraftVersions = tasks.register("checkAcceptedMinecraftVersions") {
    group = "verification"
    description = "Fails if WdlForge's @Mod acceptedMinecraftVersions does not name minecraft_version"
    // Captured by value at configuration time, like checkForgeFloor's own locals, so no Project reference
    // survives into task execution.
    val minecraftVersion = band("minecraft_version")
    val entrypointSource = layout.projectDirectory
        .file("src/main/java/world/thearchive/wdl/forge/WdlForge.java")
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.file(entrypointSource)
    doLast {
        val declared = Regex("acceptedMinecraftVersions\\s*=\\s*\"([^\"]+)\"")
            .find(entrypointSource.asFile.readText())?.groupValues?.get(1)
            ?: throw GradleException(
                "WdlForge declares no acceptedMinecraftVersions in its @Mod annotation; legacy FML then loads "
                    + "the mod on any Minecraft version, including ones whose save shape this plug cannot write"
            )
        val expected = "[$minecraftVersion]"
        if (declared != expected) {
            throw GradleException(
                "Accepted-version mismatch: minecraft_version is $minecraftVersion so the annotation must read "
                    + "$expected but WdlForge declares $declared. The annotation is the one legacy FML enforces, "
                    + "and a wrong value stops the mod loading entirely."
            )
        }
    }
}

// Reads the finished ship jar and proves the fastutil bundle actually shipped. Every other gate on this island
// passes whether or not those classes are in the jar: checkShipJar scans for mapping leaks and searge-shaped
// literals, the compile resolves fastutil off the game's own classpath either way, and the reobf pass has nothing
// to say about a third-party package. Without a check that opens the artifact, the defect is invisible until a
// real client throws NoClassDefFoundError on the first download.
abstract class CheckFastutilBundle : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputDirectory
    abstract val bundledClasses: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTrees: ConfigurableFileCollection

    @get:InputFiles
    abstract val shippedJar: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val bundleRoot = bundledClasses.get().asFile
        val expected = bundleRoot.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(bundleRoot).invariantSeparatorsPath }
            .toSortedSet()
        val inJar = sortedSetOf<String>()
        ZipInputStream(jarToScan.get().asFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(FastutilClosure.PACKAGE_PREFIX) && entry.name.endsWith(".class")) {
                    inJar.add(entry.name)
                }
                entry = zin.nextEntry
            }
        }
        val scanned = jarToScan.get().asFile.name
        val failures = ArrayList<String>()

        // (1) Packaging carried the whole bundle through, and added nothing of its own.
        (expected - inJar).forEach { failures.add("missing from $scanned: $it") }
        (inJar - expected).forEach { failures.add("in $scanned but not in the computed bundle: $it") }

        ZipFile(shippedJar.singleFile).use { shipped ->
            val shippedNames = shipped.entries().toList().map { it.name }.toSet()

            // (2) Nothing shadows a class the game already loads. The bundle is unrelocated, so a duplicate would
            // leave which copy wins to classloader source order.
            inJar.filter { shippedNames.contains(it) }.forEach {
                failures.add("$scanned duplicates a class ${shippedJar.singleFile.name} already carries: $it")
            }

            // (3) Every fastutil type the shipped source imports resolves at runtime, from one side or the other.
            // Independent of how the bundle was computed: this reads the artifact and the game's own library.
            val available = inJar + shippedNames
            for (imported in FastutilClosure.importedTypes(sourceTrees.asFileTree.files)) {
                if (FastutilClosure.resolveImport(imported, available) == null) {
                    failures.add(
                        "the shipped source imports $imported but it is in neither $scanned nor "
                            + "${shippedJar.singleFile.name}, so it throws NoClassDefFoundError on first use"
                    )
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException("$name: ${failures.size} problem(s):\n" + failures.joinToString("\n"))
        }
        logger.lifecycle(
            "$name: $scanned carries ${inJar.size} bundled fastutil class(es), none of them duplicating "
                + "${shippedJar.singleFile.name}, and every fastutil type the shipped source imports resolves"
        )
    }
}

val checkFastutilBundle = tasks.register<CheckFastutilBundle>("checkFastutilBundle") {
    group = "verification"
    description = "Fails if the ship jar does not carry the fastutil classes this band's Minecraft leaves out."
    dependsOn(modJar)
    jarToScan.set(modJar.flatMap { it.archiveFile })
    bundledClasses.set(bundleFastutil.flatMap { it.outputDir })
    sourceTrees.from(rootDir.resolve("../common/src/main/java"), layout.projectDirectory.dir("src/main/java"))
    shippedJar.from(fastutilShipped)
}

tasks.named("check") {
    dependsOn("checkReobf", checkShipJar, checkReobfNegative, checkForgeFloor, checkAcceptedMinecraftVersions,
        checkFastutilBundle)
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
