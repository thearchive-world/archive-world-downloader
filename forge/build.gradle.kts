import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

// tiny-remapper (the reobf engine) and ASM (the checkReobf constant-pool scan) on the island's buildscript
// classpath, pinned to the versions the tools/mojmap-bridge generator uses; tiny-remapper resolves from the
// Fabric maven, ASM from Maven Central. Mirrors common/build.gradle.kts, which puts ASM on its buildscript
// classpath for the compile-side annotation strip. asm-commons supplies the ClassRemapper/Remapper the scan
// records references through.
buildscript {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
    dependencies {
        classpath("net.fabricmc:tiny-remapper:0.14.0")
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-commons:9.10.1")
    }
}

// The non-Fabric jar for this deep band, a hand-rolled plain-java island beside the band's Gradle-9 root. This
// band predates the Mojmap floor, so no ForgeGradle or NeoGradle resolves official Mojang mappings for 1.13.2, and
// Architectury Loom (which the 1.16.5-and-up Forge islands use) is unproven on the Gradle 8.14.5 this island runs.
// So the island carries no Loom at all: the root's Fabric-Loom provision remaps the vanilla 1.13.2 jar to Mojmap
// through the tools/mojmap-bridge mapping and publishes that jar plus its transitive libraries to
// ../common/build/island-classpath.txt, which this build reads as plain file dependencies. The Forge API the six
// glue files reference has no Mojmap-named distribution at this band either, so remapForgeApi builds one from the
// real forge-1.13.2-25.0.223 universal jar (see below). The island stays a separate build with its own wrapper
// because it runs a different Java toolchain (Java 8) than the Gradle-9 root; two wrappers, one set of coordinates
// read from the root gradle.properties.
plugins {
    java
}

// Single source of coordinates: the island reads the band's gradle.properties from the sibling Gradle-9 root
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
    // The Forge maven hosts the real forge-1.13.2-25.0.223 universal jar (the loadability-critical API the six glue
    // files bind) and net.minecraftforge:forgespi, the small mod-info SPI that carries IModInfo. Both are resolved
    // here rather than stubbed.
    maven("https://maven.minecraftforge.net/")
}

// The Minecraft toolchain the root's Fabric Loom resolved: the Mojmap-remapped 1.13.2 jar (parameter-annotation
// stripped) plus every transitive library, one absolute path per line. common's assemble depends on
// writeIslandClasspath, so a plain `./gradlew build` at the root produces this before this island ever
// configures; run ./gradlew :common:writeIslandClasspath by hand if only the island is being built.
val islandClasspathFile = rootDir.resolve("../common/build/island-classpath.txt")
require(islandClasspathFile.exists()) {
    "island classpath not found at $islandClasspathFile; run ':common:writeIslandClasspath' in the root build first"
}
val islandMinecraft = files(islandClasspathFile.readLines().filter { it.isNotBlank() })

// The main compile reads a handful of read-only vanilla fields (the connection's channel, the relative-move
// packet's entity id, the horse menu's animal) that are private or protected at the Minecraft boundary. Forge widens
// them at runtime through META-INF/accesstransformer.cfg, and the 1.14.4-and-up Forge islands widen them at compile
// through Architectury Loom's accessTransformer step; this hand-rolled island has no Loom, so widenForCompile applies
// the same widenings to the Mojmap Minecraft jar directly (translating the SRG access-transformer field coordinates
// to Mojmap through the bridge). Only the Minecraft jar is widened; the other island libraries pass through unchanged.
val mcJarMarker = "/stripped-minecraft/"
val strippedMinecraftJar = islandMinecraft.filter { it.path.replace('\\', '/').contains(mcJarMarker) }
val islandLibraries = islandMinecraft.filter { !it.path.replace('\\', '/').contains(mcJarMarker) }

// The reobf fixture set. It compiles against the SAME Mojmap Minecraft classpath the island main uses, so reobfJar
// has a compilable Mojmap-named input to reobfuscate. It is isolated from main: nothing on main depends on it and it
// depends on nothing of main's, so ./gradlew reobfJar/checkReobf runs standalone.
sourceSets {
    create("reobftest")
}

// The real Forge API the six glue files reference, met by a Mojmap-named view of the genuine forge-1.13.2-25.0.223
// universal jar rather than a hand-rolled stub: the universal's net.minecraftforge.* classes reference Minecraft by
// 1.13.2 SRG name, so remapForgeApi remaps them SRG -> Mojmap through the bridge's mojmap-srg.tiny (read in reverse)
// and keeps ONLY the net/minecraftforge/** entries, so the universal's own patched net.minecraft.* classes never
// shadow the Mojmap Minecraft the island already compiles against. forgespi supplies the mod-info SPI (IModInfo) the
// universal does not carry; it names no Minecraft, so it needs no remap.
val forgeUniversal: Configuration by configurations.creating { isTransitive = false }

val forgeApiMappingFile = rootDir.resolve("../tools/mojmap-bridge/build/bridge/mojmap-srg.tiny")
require(forgeApiMappingFile.exists()) {
    "bridge mapping not found at $forgeApiMappingFile; run ':genBridge' in tools/mojmap-bridge first"
}

// Remaps the resolved Forge universal jar SRG -> Mojmap (the bridge's mojmap-srg.tiny read srg -> mojmap) with
// tiny-remapper, then filters the output to net/minecraftforge/** classes so only the Forge API lands on the
// compile classpath. The Minecraft classpath is read (not emitted) so tiny-remapper can resolve reference targets;
// the universal names Minecraft by SRG, so its type references are rewritten to their Mojmap names by the class
// mapping while the Forge classes themselves keep their own names.
abstract class RemapForgeApi : DefaultTask() {
    @get:InputFiles
    abstract val universalJar: ConfigurableFileCollection

    @get:InputFiles
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:OutputFile
    abstract val forgeApiJar: RegularFileProperty

    @TaskAction
    fun remap() {
        val out = forgeApiJar.get().asFile
        out.parentFile.mkdirs()
        val remapped = File(out.parentFile, "forge-api-remapped-all.jar")
        Files.deleteIfExists(remapped.toPath())
        Files.deleteIfExists(out.toPath())
        val provider = TinyUtils.createTinyMappingProvider(mapping.get().asFile.toPath(), "srg", "mojmap")
        val remapper = TinyRemapper.newRemapper().withMappings(provider).ignoreConflicts(true).build()
        try {
            OutputConsumerPath.Builder(remapped.toPath()).build().use { consumer ->
                remapper.readClassPath(*minecraftClasspath.files.map { it.toPath() }.toTypedArray())
                remapper.readInputs(universalJar.singleFile.toPath())
                remapper.apply(consumer)
            }
        } finally {
            remapper.finish()
        }
        var kept = 0
        ZipOutputStream(out.outputStream().buffered()).use { zout ->
            ZipInputStream(remapped.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".class") && entry.name.startsWith("net/minecraftforge/")) {
                        val bytes = zin.readBytes()
                        val copy = ZipEntry(entry.name)
                        copy.time = 0L // fixed timestamp for determinism
                        zout.putNextEntry(copy)
                        zout.write(bytes)
                        zout.closeEntry()
                        kept++
                    }
                    entry = zin.nextEntry
                }
            }
        }
        Files.deleteIfExists(remapped.toPath())
        logger.lifecycle("forge-api view: remapped universal srg -> mojmap, kept $kept net/minecraftforge/** classes -> ${out.name}")
    }
}

val remapForgeApi = tasks.register<RemapForgeApi>("remapForgeApi") {
    group = "build"
    description = "Remaps the Forge universal jar SRG -> Mojmap and filters it to net/minecraftforge/** for the island compile view."
    universalJar.from(forgeUniversal)
    minecraftClasspath.from(islandMinecraft)
    mapping.set(forgeApiMappingFile)
    forgeApiJar.set(layout.buildDirectory.file("forge-api/forge-api-mojmap.jar"))
}

// Copies the Mojmap Minecraft jar with the access-transformer's read-only field widenings applied, so the main
// compile can reach the fields the tee and mount-menu bind read. It parses META-INF/accesstransformer.cfg (SRG
// class token plus SRG field id), translates each to its Mojmap class and field through the bridge (the SRG field id
// is globally unique, so the field translation needs no class context), and rewrites those field access flags to
// public in a full copy of the jar. Every other class passes through byte-for-byte.
abstract class WidenForCompile : DefaultTask() {
    @get:InputFiles
    abstract val minecraftJar: ConfigurableFileCollection

    @get:InputFile
    abstract val accessTransformer: RegularFileProperty

    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:OutputFile
    abstract val widenedJar: RegularFileProperty

    @TaskAction
    fun widen() {
        val srgTargets = ArrayList<Pair<String, String>>()
        accessTransformer.get().asFile.forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) {
                return@forEachLine
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 3) {
                srgTargets.add(parts[1].replace('.', '/') to parts[2])
            }
        }
        val classMap = HashMap<String, String>()
        val fieldMap = HashMap<String, String>()
        mapping.get().asFile.forEachLine { line ->
            if (line.startsWith("c\t")) {
                val cols = line.split("\t")
                if (cols.size >= 3) {
                    classMap[cols[2]] = cols[1]
                }
            } else if (line.startsWith("\tf\t")) {
                val cols = line.split("\t")
                if (cols.size >= 5) {
                    fieldMap[cols[4]] = cols[3]
                }
            }
        }
        val targets = HashMap<String, MutableSet<String>>()
        for ((srgClass, srgField) in srgTargets) {
            val mojmapClass = classMap[srgClass] ?: error("no Mojmap class for access-transformer class $srgClass")
            val mojmapField = fieldMap[srgField] ?: error("no Mojmap field for access-transformer field $srgField")
            targets.getOrPut(mojmapClass) { HashSet() }.add(mojmapField)
        }

        val out = widenedJar.get().asFile
        out.parentFile.mkdirs()
        Files.deleteIfExists(out.toPath())
        var widened = 0
        ZipOutputStream(out.outputStream().buffered()).use { zout ->
            ZipInputStream(minecraftJar.singleFile.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val bytes = zin.readBytes()
                    val fields = if (entry.name.endsWith(".class")) targets[entry.name.removeSuffix(".class")] else null
                    val output = if (fields == null) {
                        bytes
                    } else {
                        val writer = ClassWriter(0)
                        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                            override fun visitField(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                value: Any?,
                            ): FieldVisitor {
                                var effective = access
                                if (fields.contains(name)) {
                                    effective = access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv() or
                                        Opcodes.ACC_PUBLIC
                                    widened++
                                }
                                return super.visitField(effective, name, descriptor, signature, value)
                            }
                        }, 0)
                        writer.toByteArray()
                    }
                    val copy = ZipEntry(entry.name)
                    copy.time = 0L // fixed timestamp for determinism
                    zout.putNextEntry(copy)
                    zout.write(output)
                    zout.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        logger.lifecycle("widen-for-compile: widened $widened field(s) across ${targets.size} class(es) -> ${out.name}")
    }
}

val widenForCompile = tasks.register<WidenForCompile>("widenForCompile") {
    group = "build"
    description = "Applies the access-transformer field widenings to the Mojmap Minecraft jar for the island compile view."
    minecraftJar.from(strippedMinecraftJar)
    accessTransformer.set(file("src/main/resources/META-INF/accesstransformer.cfg"))
    mapping.set(forgeApiMappingFile)
    widenedJar.set(layout.buildDirectory.file("widen/minecraft-widened.jar"))
}

dependencies {
    forgeUniversal("net.minecraftforge:forge:${band("forge_version")}:universal")

    "reobftestCompileOnly"(islandMinecraft)

    // The main compile sees the widened Minecraft jar (access-transformer fields opened) in place of the raw
    // stripped jar, plus the other island libraries unchanged.
    compileOnly(islandLibraries)
    compileOnly(files(widenForCompile.flatMap { it.widenedJar }))
    // The Mojmap-named Forge API view (remapped universal, net/minecraftforge/** only). The universal names none of
    // its own supporting libraries, so the three the glue transitively reaches are declared alongside it, at the
    // exact versions the 1.13.2 installer profile pins: eventbus (the Event base type every Forge event extends),
    // forgespi (the IModInfo mod-info SPI ModContainer returns), and maven-artifact (the ArtifactVersion IModInfo's
    // version is). None names Minecraft, so none is remapped.
    compileOnly(files(remapForgeApi.flatMap { it.forgeApiJar }))
    compileOnly("net.minecraftforge:eventbus:0.9.3")
    compileOnly("net.minecraftforge:forgespi:0.13.0")
    compileOnly("org.apache.maven:maven-artifact:3.6.0")

    // JSpecify (@NullMarked / @Nullable), compile-only and CLASS-retention: the source-merged common/ and the
    // shim are null-marked. NullAway itself does not run on this island (it is a Gradle-9 build-logic pass over
    // common + fabric); here the annotations only need to resolve so the marked source compiles.
    compileOnly("org.jspecify:jspecify:1.0.0")

    // JourneyMap public API for the source-merged binding (compat/journeymap), compile-only, never a runtime
    // require. The island compiles under the same Mojmap classpath :common resolves; JourneyMap discovers the
    // plugin by annotation scan. No XaeroPlus binding on this band, matching :common.
    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-SNAPSHOT")
}

// Source-merge :common the way wdl.common-merge does for the Gradle-9 loaders, but by direct path since the
// island is a separate build with no access to :common's consumable configurations: fold common's main source
// into this compile and its resources into the jar.
tasks.named<JavaCompile>("compileJava") {
    source(rootDir.resolve("../common/src/main/java"))
}

tasks.named<ProcessResources>("processResources") {
    from(rootDir.resolve("../common/src/main/resources")) {
        exclude("**/.gitkeep")
    }
    // Keep mods.toml's and wdl-publishing.properties' templated fields in sync with the band coordinates,
    // matching the fabric/neoforge processResources. The Forge floor (forge_version_min) is a deliberate value
    // distinct from the build coordinate forge_version.
    val tokens = mapOf(
        "version" to version.toString(),
        "minecraft_version" to band("minecraft_version"),
        "forge_version_min" to band("forge_version_min"),
        "modrinth_id" to band("modrinth_id"),
        "mod_id" to band("mod_id"),
    )
    inputs.properties(tokens)
    filesMatching("META-INF/mods.toml") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// --- Reobfuscation (Mojmap -> 1.13.2 SRG) + the checkReobf validation gate ---
// The runtime name scheme below the Mojmap floor is full SRG: net/minecraft class names with func_*/field_*
// members. common is written in Mojmap names and compiles against the Mojmap-remapped 1.13.2 jar, so the shipped
// output must be reobfuscated Mojmap -> SRG before Forge loads it. The reobf mapping is the mojmap-srg.tiny the
// tools/mojmap-bridge generator derives from the SAME obf-mojmap source as the intermediary-mojmap compile
// mapping; the SRG side is the SRG column of mcp_config 1.13.2's joined.tsrg. Like genBridge and
// writeIslandClasspath, both files are produced by a PREREQUISITE generator run and must already exist here.
//
// reobfJar reobfuscates the REAL merged-main island output (../common/src/main/java + the forge glue); the
// reobftest fixture is retained only to feed checkReobfNegative, which proves the gate fires on a known-dirty
// (un-reobfuscated) input. checkReobf catches UN- or MIS-resolution (a leaked Mojmap class name, a surviving
// com/mojang/blaze3d reference, or a member left un-searge'd), NOT a wrong-but-valid class match: that a class
// reobfs to a real but incorrect SRG class is caught by the generator's structural gate and the registry-boot
// smoke test, which is why those exist.
val reobfMappingFile = rootDir.resolve("../tools/mojmap-bridge/build/bridge/mojmap-srg.tiny")
val srgOracleFile = rootDir.resolve("../tools/mojmap-bridge/build/sources/joined_1132.tsrg")
require(reobfMappingFile.exists()) {
    "reobf mapping not found at $reobfMappingFile; run ':genBridge' in tools/mojmap-bridge first"
}
require(srgOracleFile.exists()) {
    "1.13.2 SRG oracle (joined.tsrg) not found at $srgOracleFile; run ':genBridge' in tools/mojmap-bridge first"
}

// Packages compiled (Mojmap-named) classes into a deterministic jar, then reobfuscates it Mojmap -> SRG with
// tiny-remapper over the mojmap-srg.tiny mapping. The mod's own world.thearchive.wdl.* classes are absent from
// the mapping, so tiny-remapper leaves them untouched and rewrites only their Minecraft references. The
// Minecraft classpath is read (not emitted) so tiny-remapper can resolve the reference hierarchy. Both the real
// merged-main output (reobfJar) and the reobftest fixture (reobfFixtureJar, for the negative gate) run through it.
abstract class ReobfMojmapToSrg : DefaultTask() {
    @get:InputFiles
    abstract val classesDirs: ConfigurableFileCollection

    @get:InputFiles
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:InputFile
    abstract val mapping: RegularFileProperty

    @get:OutputFile
    abstract val mojmapJar: RegularFileProperty

    @get:OutputFile
    abstract val srgJar: RegularFileProperty

    @TaskAction
    fun reobf() {
        val mojJar = mojmapJar.get().asFile
        mojJar.parentFile.mkdirs()
        ZipOutputStream(mojJar.outputStream().buffered()).use { zout ->
            for (dir in classesDirs.files) {
                if (!dir.isDirectory) {
                    continue
                }
                val root = dir.toPath()
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .sortedBy { it.path }
                    .forEach { classFile ->
                        val entry = ZipEntry(root.relativize(classFile.toPath()).toString().replace('\\', '/'))
                        entry.time = 0L // fixed timestamp for determinism
                        zout.putNextEntry(entry)
                        zout.write(classFile.readBytes())
                        zout.closeEntry()
                    }
            }
        }

        val srg = srgJar.get().asFile
        srg.parentFile.mkdirs()
        Files.deleteIfExists(srg.toPath())
        val provider = TinyUtils.createTinyMappingProvider(mapping.get().asFile.toPath(), "mojmap", "srg")
        val remapper = TinyRemapper.newRemapper().withMappings(provider).build()
        try {
            OutputConsumerPath.Builder(srg.toPath()).build().use { consumer ->
                remapper.readClassPath(*minecraftClasspath.files.map { it.toPath() }.toTypedArray())
                remapper.readInputs(mojJar.toPath())
                remapper.apply(consumer)
            }
        } finally {
            remapper.finish()
        }
        logger.lifecycle("reobf: ${mojJar.name} (mojmap) -> ${srg.name} (srg)")
    }
}

// Scans a jar's constant pools (via ASM ClassRemapper, which visits every class reference and every member
// reference) and fails on either arm:
//   (a) a net/minecraft class reference that is not a valid 1.13.2 SRG class name (a leaked Mojmap class name,
//       which would be a runtime NoClassDefFoundError), OR any surviving com/mojang/blaze3d reference. The
//       blaze3d package DOES exist at 1.13.2 (e.g. com/mojang/blaze3d/platform/GlStateManager), but every
//       blaze3d class reobfuscates to a net/minecraft SRG name (mojmap-srg maps them all to net/minecraft/*),
//       so a correctly reobfuscated 1.13.2 jar carries zero blaze3d references; a survivor is an unmapped leak,
//       the same failure class as a leaked Mojmap net/minecraft name. The valid net/minecraft set is the SRG
//       column of joined.tsrg.
//   (b) a member on a net/minecraft owner whose name is not a valid 1.13.2 searge member id and is not a JVM
//       special (<init>/<clinit>) or a java.lang.Object method (a Mojmap or Legacy Fabric intermediary member
//       with no mojmap-srg entry, which would be a runtime NoSuchMethod/FieldError). The valid set is every
//       srg member name in joined.tsrg; a name test alone would pass a leaked intermediary field_NNN, which
//       shares the field_ prefix with searge field ids. A leaked name that is also a known Mojmap member
//       (present in mojmap-srg) is annotated as such. A non-searge name is NOT a leak when it resolves to a
//       member inherited from a JDK or library supertype (java.lang.Enum.ordinal, a JDK-collection addAll):
//       such names are un-obfuscatable and correct at runtime. That is decided by hierarchy resolution, not a
//       by-name allowlist (which could hide a real leaked Mojmap method sharing a JDK name): the owner is
//       reverse-mapped srg -> mojmap and its supertypes are walked over the Mojmap Minecraft jar; the member
//       is a leak only if some net/minecraft class in that hierarchy actually declares it (matched by name and
//       srg-normalized descriptor). A member no scanned vanilla class declares resolves to a JDK/library type
//       and is safe. An owner that does not reverse-map (a Mojmap-named class in the negative fixture) fails
//       closed as a leak.
// expectClean flips the polarity: the pass gate (true) fails on any offender; the negative gate (false) fails
// when NO offender is found, since a gate never seen to fire is not trusted.
abstract class CheckReobf : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputFile
    abstract val srgClassSource: RegularFileProperty

    @get:InputFile
    abstract val mojmapSrgMapping: RegularFileProperty

    // The Mojmap-named Minecraft jar, read for the arm (b) hierarchy resolution: a flagged member's owner is
    // reverse-mapped srg -> mojmap and its supertypes walked here to tell a JDK-inherited member from a real
    // net/minecraft leak.
    @get:InputFiles
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Input
    abstract val expectClean: Property<Boolean>

    @TaskAction
    fun check() {
        val validSrgClasses = HashSet<String>()
        val validSrgMembers = HashSet<String>()
        srgClassSource.get().asFile.forEachLine { line ->
            if (line.isEmpty()) {
                return@forEachLine
            }
            if (line[0] != '\t') {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    validSrgClasses.add(parts[1])
                }
            } else {
                // A member line is "\t<obf> <srg>" (field) or "\t<obf> <obfDesc> <srg>" (method); the srg
                // name is the last whitespace token. Intermediary field ids (field_595) share the field_
                // prefix with searge field ids (field_178784_b) but never appear here, so validating the
                // name against this set catches a leaked intermediary member a prefix test would pass.
                val parts = line.trim().split(" ")
                if (parts.isNotEmpty()) {
                    validSrgMembers.add(parts.last())
                }
            }
        }
        val mojmapMemberNames = HashSet<String>()
        mojmapSrgMapping.get().asFile.forEachLine { line ->
            if (line.startsWith("\t")) {
                val cols = line.split("\t")
                if (cols.size >= 5 && (cols[1] == "m" || cols[1] == "f")) {
                    mojmapMemberNames.add(cols[3])
                }
            }
        }

        val classRefs = LinkedHashSet<String>()
        val memberRefs = ArrayList<Array<String>>()
        ZipInputStream(jarToScan.get().asFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".class")) {
                    val bytes = zin.readBytes()
                    val recorder = object : Remapper(Opcodes.ASM9) {
                        override fun map(internalName: String): String {
                            classRefs.add(internalName)
                            return internalName
                        }

                        override fun mapMethodName(owner: String, name: String, descriptor: String): String {
                            memberRefs.add(arrayOf(owner, name, descriptor))
                            return name
                        }

                        override fun mapFieldName(owner: String, name: String, descriptor: String): String {
                            memberRefs.add(arrayOf(owner, name, descriptor))
                            return name
                        }
                    }
                    ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), recorder), 0)
                }
                entry = zin.nextEntry
            }
        }

        fun isMinecraft(name: String) = name.startsWith("net/minecraft/")
        fun isBlaze3dLeak(name: String) = name.startsWith("com/mojang/blaze3d/")

        val objectMethods = setOf(
            "equals", "hashCode", "toString", "clone", "getClass",
            "notify", "notifyAll", "wait", "finalize",
        )

        // Mojmap-space class hierarchy from the Mojmap Minecraft jar: superclass, interfaces, and the
        // declared method (name + Mojmap descriptor) and field names per class, for the arm (b) resolution.
        val mcSuper = HashMap<String, String>()
        val mcInterfaces = HashMap<String, List<String>>()
        val mcMethods = HashMap<String, MutableSet<String>>()
        val mcFields = HashMap<String, MutableSet<String>>()
        for (jar in minecraftClasspath.files) {
            if (!jar.path.endsWith(".jar")) {
                continue
            }
            ZipInputStream(jar.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".class")) {
                        val bytes = zin.readBytes()
                        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                            var cur = ""

                            override fun visit(
                                version: Int,
                                access: Int,
                                name: String,
                                signature: String?,
                                superName: String?,
                                interfaces: Array<String>?,
                            ) {
                                cur = name
                                if (superName != null) {
                                    mcSuper[name] = superName
                                }
                                if (interfaces != null && interfaces.isNotEmpty()) {
                                    mcInterfaces[name] = interfaces.toList()
                                }
                            }

                            override fun visitMethod(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                exceptions: Array<String>?,
                            ): MethodVisitor? {
                                mcMethods.getOrPut(cur) { HashSet() }.add("$name\t$descriptor")
                                return null
                            }

                            override fun visitField(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                value: Any?,
                            ): FieldVisitor? {
                                mcFields.getOrPut(cur) { HashSet() }.add(name)
                                return null
                            }
                        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                    }
                    entry = zin.nextEntry
                }
            }
        }
        val mojToSrgClass = HashMap<String, String>()
        val srgToMojClass = HashMap<String, String>()
        mojmapSrgMapping.get().asFile.forEachLine { line ->
            if (line.startsWith("c\t")) {
                val cols = line.split("\t")
                if (cols.size >= 3) {
                    mojToSrgClass[cols[1]] = cols[2]
                    srgToMojClass[cols[2]] = cols[1]
                }
            }
        }
        fun normDescMojToSrg(desc: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < desc.length) {
                val c = desc[i]
                if (c == 'L') {
                    val semi = desc.indexOf(';', i)
                    val cls = desc.substring(i + 1, semi)
                    sb.append('L').append(mojToSrgClass[cls] ?: cls).append(';')
                    i = semi + 1
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
        // True when some net/minecraft class in the owner's Mojmap hierarchy actually declares the member,
        // i.e. it resolves to a reobfuscatable vanilla slot rather than an inherited JDK/library one. A member
        // no walked vanilla class declares resolves to a JDK/library supertype and is not a leak. An owner
        // with no srg -> mojmap entry fails closed (returns true), so the Mojmap-named negative fixture keeps
        // flagging.
        fun mcDeclaresMember(srgOwner: String, name: String, srgDesc: String, isMethod: Boolean): Boolean {
            val mojOwner = srgToMojClass[srgOwner] ?: return true
            val queue = ArrayDeque<String>()
            val seen = HashSet<String>()
            queue.add(mojOwner)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (!seen.add(c)) {
                    continue
                }
                if (isMethod) {
                    val decls = mcMethods[c]
                    if (decls != null) {
                        for (d in decls) {
                            val tab = d.indexOf('\t')
                            if (d.substring(0, tab) == name && normDescMojToSrg(d.substring(tab + 1)) == srgDesc) {
                                return true
                            }
                        }
                    }
                } else if (mcFields[c]?.contains(name) == true) {
                    return true
                }
                mcSuper[c]?.let { queue.add(it) }
                mcInterfaces[c]?.let { queue.addAll(it) }
            }
            return false
        }

        val offenders = ArrayList<String>()
        for (ref in classRefs.toSortedSet()) {
            if (isBlaze3dLeak(ref)) {
                offenders.add(
                    "(a) surviving com/mojang/blaze3d reference (reobfuscates to a net/minecraft SRG name at "
                        + "1.13.2, so a survivor is unmapped): $ref"
                )
            } else if (isMinecraft(ref) && !validSrgClasses.contains(ref)) {
                offenders.add("(a) leaked Mojmap class name (not a valid 1.13.2 SRG class): $ref")
            }
        }
        val seenMembers = HashSet<String>()
        for (member in memberRefs) {
            val owner = member[0]
            val name = member[1]
            val descriptor = member[2]
            if (!isMinecraft(owner) || name == "<init>" || name == "<clinit>"
                || objectMethods.contains(name) || validSrgMembers.contains(name)) {
                continue
            }
            if (!seenMembers.add("$owner.$name$descriptor")) {
                continue
            }
            // A method descriptor starts with '(' (memberRefs mixes methods and fields); a field carries a
            // plain type descriptor. Skip a member that resolves to a JDK/library supertype: it is not a leak.
            val isMethod = descriptor.startsWith("(")
            if (!mcDeclaresMember(owner, name, descriptor, isMethod)) {
                continue
            }
            val note = if (mojmapMemberNames.contains(name)) " [known Mojmap member -> member-consistency gap]" else ""
            offenders.add("(b) member did not reobfuscate to a searge id: $owner.$name$descriptor$note")
        }

        val scanned = jarToScan.get().asFile.name
        if (expectClean.get()) {
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "checkReobf: ${offenders.size} offender(s) in $scanned:\n" + offenders.joinToString("\n")
                )
            }
            val mcRefs = classRefs.count { isMinecraft(it) }
            logger.lifecycle(
                "checkReobf: $scanned clean; $mcRefs net/minecraft class refs all valid 1.13.2 SRG, "
                    + "every net/minecraft member reobfuscated to a searge id, zero com/mojang/blaze3d survivors"
            )
        } else {
            if (offenders.isEmpty()) {
                throw GradleException(
                    "checkReobfNegative: expected the gate to FIRE on $scanned but found no offenders; "
                        + "a gate never seen to fire is not trusted"
                )
            }
            logger.lifecycle(
                "checkReobfNegative: gate fired as required on $scanned; ${offenders.size} offender(s), e.g.\n"
                    + offenders.take(8).joinToString("\n")
            )
        }
    }
}

val reobfJar = tasks.register<ReobfMojmapToSrg>("reobfJar") {
    group = "build"
    description = "Reobfuscates the real merged-main island output Mojmap -> 1.13.2 SRG (leaving world.thearchive.wdl.* untouched)."
    classesDirs.from(sourceSets["main"].output.classesDirs)
    minecraftClasspath.from(islandMinecraft)
    mapping.set(reobfMappingFile)
    mojmapJar.set(layout.buildDirectory.file("reobf/main-mojmap.jar"))
    srgJar.set(layout.buildDirectory.file("reobf/main-srg.jar"))
}

// The reobftest fixture keeps its own reobf pass, pinned to the fixture (never the real main), so the negative
// gate always has a known-dirty input: checkReobfNegative scans this task's Mojmap-named (un-reobfuscated)
// packaged jar and fails if the gate does NOT flag it. Its srgJar output is unused; only the mojmap side feeds
// the negative gate. Keeping it separate from reobfJar is what lets reobfJar point at the real main while the
// negative gate stays on the fixture.
val reobfFixtureJar = tasks.register<ReobfMojmapToSrg>("reobfFixtureJar") {
    group = "build"
    description = "Packages the reobfTest fixture (Mojmap-named) for checkReobfNegative and reobfuscates it Mojmap -> 1.13.2 SRG."
    classesDirs.from(sourceSets["reobftest"].output.classesDirs)
    minecraftClasspath.from(islandMinecraft)
    mapping.set(reobfMappingFile)
    mojmapJar.set(layout.buildDirectory.file("reobf/reobftest-mojmap.jar"))
    srgJar.set(layout.buildDirectory.file("reobf/reobftest-srg.jar"))
}

tasks.register<CheckReobf>("checkReobf") {
    group = "verification"
    description = "Fails if the reobf'd main jar leaks a Mojmap class name or a com/mojang/blaze3d survivor (arm a) or an un-searge'd Minecraft member (arm b)."
    dependsOn(reobfJar)
    jarToScan.set(reobfJar.flatMap { it.srgJar })
    srgClassSource.set(srgOracleFile)
    mojmapSrgMapping.set(reobfMappingFile)
    minecraftClasspath.from(strippedMinecraftJar)
    expectClean.set(true)
}

tasks.register<CheckReobf>("checkReobfNegative") {
    group = "verification"
    description = "Proves checkReobf fires: scans the un-reobfuscated (Mojmap-named) fixture jar; the build fails if the gate does NOT flag it."
    dependsOn(reobfFixtureJar)
    jarToScan.set(reobfFixtureJar.flatMap { it.mojmapJar })
    srgClassSource.set(srgOracleFile)
    mojmapSrgMapping.set(reobfMappingFile)
    minecraftClasspath.from(strippedMinecraftJar)
    expectClean.set(false)
}

// --- Ship jar (modJar) + the checkShipJar class-file health gate ---
// The shipped Forge jar must be the reobf'd, SRG-named artifact. The java plugin's default `jar` archives
// sourceSets.main.output, whose classes are Mojmap-named (pre-reobf) and so cannot ship correct runtime names,
// and its from(sourceSet.output) build dependency on compileJava cannot be removed through any public API. So
// the ship artifact is a fresh Jar, decoupled from the main compile by construction: it sources the reobf'd
// classes from reobfJar plus the token-expanded resources (mods.toml, accesstransformer.cfg, pack.mcmeta, common
// lang/assets) from processResources. This mirrors the higher bands' remapJar-as-ship-artifact split, where the
// default jar is the dev jar and the remapped one is what ships. reobfJar targets the real merged-main output,
// so modJar ships the real SRG-named classes.
val modJar = tasks.register<Jar>("modJar") {
    group = "build"
    description = "Assembles the shippable Forge mod jar: reobf'd SRG classes + resources + accesstransformer.cfg."
    dependsOn(reobfJar)
    // A fresh Jar defaults archiveBaseName to the project name; restate the -forge coordinate base sets on the
    // default jar so the ship artifact is archive-wdl-forge-<version>.jar.
    archiveBaseName.set("${band("mod_archives_base")}-forge")
    // Byte-stable for reproducibility.yml: sorted entries and a fixed timestamp normalize away tiny-remapper's
    // un-pinned OutputConsumerPath entry times, so two builds of the shipped artifact hash identically.
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    from(zipTree(reobfJar.flatMap { it.srgJar }))
    from(tasks.named("processResources"))
}

// The default jar carries a dev classifier so it never collides with modJar's ship name. It stays the
// Mojmap-named dev jar; modJar is the artifact CI uploads.
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

// build/assemble produce the ship jar. modJar also builds standalone via ./gradlew modJar.
tasks.named("assemble") {
    dependsOn(modJar)
}

// Class-file health gate over the shipped jar. Two arms:
//   (a) every class parses (an ASM ClassReader pass over each .class entry); a malformed class is a runtime
//       failure the reobf pipeline could otherwise ship silently.
//   (b) no net/minecraft descriptor carrying a jspecify TYPE_USE @Nullable still names a Mojmap class. The
//       source-merged common is @NullMarked with CLASS-retention @Nullable, so a nullable Minecraft-typed member
//       ships a RuntimeInvisibleTypeAnnotations entry whose annotated member descriptor tiny-remapper must have
//       remapped Mojmap -> SRG. Arm (b) re-extracts the net/minecraft names from each TYPE_USE-annotated member's
//       descriptor and signature and fails on any that is not a valid 1.13.2 SRG class (the joined.tsrg oracle,
//       the same set checkReobf validates against). The @Nullable's own descriptor is org.jspecify.*, never
//       net/minecraft, so the load-bearing name is the annotated member's descriptor.
// modJar ships the real merged-main output, so arm (a) parses every real shipped class. Arm (b) checks each
// TYPE_USE-annotated Minecraft-typed member descriptor for a surviving Mojmap net/minecraft name; the @NullMarked
// common puts @Nullable on Minecraft-typed members, so real input can exercise it.
abstract class CheckShipJar : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputFile
    abstract val srgClassSource: RegularFileProperty

    @TaskAction
    fun check() {
        val validSrgClasses = HashSet<String>()
        srgClassSource.get().asFile.forEachLine { line ->
            if (line.isNotEmpty() && line[0] != '\t') {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    validSrgClasses.add(parts[1])
                }
            }
        }

        val mcName = Regex("net/minecraft/[A-Za-z0-9_/\$]+")
        val parseFailures = ArrayList<String>()
        val leaks = ArrayList<String>()
        var classes = 0
        var annotatedMembers = 0

        ZipInputStream(jarToScan.get().asFile.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".class")) {
                    val bytes = zin.readBytes()
                    val annotated = ArrayList<Pair<String, String?>>()
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
                                ): FieldVisitor = object : FieldVisitor(Opcodes.ASM9) {
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
                                    if (!validSrgClasses.contains(match.value)) {
                                        leaks.add(
                                            "(b) ${entry.name}: TYPE_USE-annotated member carries non-SRG "
                                                + "net/minecraft name ${match.value}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }

        val scanned = jarToScan.get().asFile.name
        val offenders = parseFailures + leaks
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "checkShipJar: ${offenders.size} offender(s) in $scanned:\n" + offenders.joinToString("\n")
            )
        }
        logger.lifecycle(
            "checkShipJar: $scanned healthy; $classes class(es) parsed (arm a); arm (b) scanned "
                + "$annotatedMembers TYPE_USE-annotated member descriptor(s), no leaked Mojmap net/minecraft name"
        )
    }
}

val checkShipJar = tasks.register<CheckShipJar>("checkShipJar") {
    group = "verification"
    description = "Fails if a shipped class fails to parse (arm a) or a TYPE_USE-annotated member still names a Mojmap Minecraft class (arm b)."
    dependsOn(modJar)
    jarToScan.set(modJar.flatMap { it.archiveFile })
    srgClassSource.set(srgOracleFile)
}

tasks.named("check") {
    dependsOn("checkReobf", "checkReobfNegative", checkShipJar)
}
