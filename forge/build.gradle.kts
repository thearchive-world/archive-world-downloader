import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-commons:9.10.1")
    }
}

plugins {
    java
    id("xyz.wagyourtail.unimined") version "1.4.1"
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

val band = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun band(key: String): String = band.getProperty(key) ?: error("missing '$key' in ../gradle.properties")

group = band("mod_group")
// Match wdl.java-conventions: the MC version rides as SemVer build metadata.
version = "${band("mod_version")}+${band("minecraft_version")}"

base {
    archivesName.set("${band("mod_archives_base")}-forge")
}

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
    // names. It is the reference set the bundle subtracts against, so it has to be the game's exact artifact rather
    // than a same-version stand-in from Maven Central.
    maven("https://libraries.minecraft.net/") { content { includeModule("it.unimi.dsi", "fastutil") } }
    maven("https://maven.minecraftforge.net/") { content { includeGroup("de.oceanlabs.mcp") } }
}

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

val seargeOracle: Configuration = configurations.create("seargeOracle") { isTransitive = false }

// This band's joined.srg parses to exactly these counts under a correct SRG v1 reader: 2980 CL: records, and 18354
// distinct searge member ids (9693 field_* and 8661 func_*).
val seargeOracleClasses = 2980
val seargeOracleMembers = 18354

dependencies {
    seargeOracle("de.oceanlabs.mcp:mcp:${band("minecraft_version")}:srg@zip")
}

// joined.srg maps obfuscated names to real names one self-contained line at a time, in SRG v1: every line carries
// its own record type as a prefix and there is no indentation and no scoping anywhere in the file, unlike the
// TSRG the 1.12.2 and 1.13.2 islands read, where a class line opens a block of tab-indented member lines beneath it.
// The four record types and their columns:
//
//   PK: <obfPackage> <realPackage>
//   CL: <obfClass> <realClass>
//   FD: <obfOwner>/<obfField> <realOwner>/<seargeField>
//   MD: <obfOwner>/<obfMethod> <obfDescriptor> <realOwner>/<seargeMethod> <realDescriptor>
//
// A member name is fully qualified, so the searge id is the last SLASH-separated segment of its target column, never
// the whole token. And on an MD: line the target column is field 4, not the line's last token, which is the trailing
// descriptor; a last-token parse over this file returns no func_* ids. Keying a record on its token count instead of
// its prefix is the other trap: PK:, CL: and FD: lines all carry three tokens, so an arity test sweeps package and
// field names into the class set.
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
    description = "Extracts joined.srg (the 1.10.2 searge oracle) from the pinned mcp srg zip to a stable build/ path."
    seargeZip.from(seargeOracle)
    joinedSrg.set(layout.buildDirectory.file("searge-oracle/joined.srg"))
}

sourceSets {
    create("reobftest")
}

val jspecify = "org.jspecify:jspecify:1.0.0"

dependencies {
    compileOnly(jspecify)

    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}")

    // fastutil: this compile source-merges common, whose core/ subtree binds types this band's trimmed Minecraft
    // fastutil leaves out.
    compileOnly("it.unimi.dsi:fastutil:${band("fastutil_bundle_version")}")
}

// The Target on NullMarked names ElementType.MODULE, a constant Java 8 lacks, so javac 8 and javadoc 8 each warn
// "unknown enum constant" whenever they read the class.
abstract class StripNullMarkedModuleTarget : DefaultTask() {
    @get:InputFiles
    abstract val jspecifyJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun strip() {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }
        val jar = jspecifyJar.singleFile
        val target = outDir.resolve(jar.nameWithoutExtension + "-stripped.jar")
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
            ZipOutputStream(target.outputStream().buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val bytes = zin.readBytes()
                    val nullMarked = entry.name == "org/jspecify/annotations/NullMarked.class"
                    val transformed = if (nullMarked) stripModuleTarget(bytes) else bytes
                    zout.putNextEntry(ZipEntry(entry.name))
                    zout.write(transformed)
                    zout.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
    }

    private fun stripModuleTarget(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                val annotation = super.visitAnnotation(descriptor, visible)
                if (annotation == null || descriptor != "Ljava/lang/annotation/Target;") return annotation
                return object : AnnotationVisitor(Opcodes.ASM9, annotation) {
                    override fun visitArray(name: String?): AnnotationVisitor? {
                        val array = super.visitArray(name) ?: return null
                        return object : AnnotationVisitor(Opcodes.ASM9, array) {
                            override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                                if (value != "MODULE") super.visitEnum(name, descriptor, value)
                            }
                        }
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }
}

val strippedJspecifyDir = layout.buildDirectory.dir("stripped-jspecify")
val stripNullMarkedModuleTarget = tasks.register<StripNullMarkedModuleTarget>("stripNullMarkedModuleTarget") {
    jspecifyJar.from(configurations.detachedConfiguration(dependencies.create(jspecify)))
    outputDir.set(strippedJspecifyDir)
}
val strippedJspecify = fileTree(strippedJspecifyDir) { include("*.jar") }.builtBy(stripNullMarkedModuleTarget)
sourceSets.configureEach {
    compileClasspath = compileClasspath.filter { !it.name.startsWith("jspecify-") } + strippedJspecify
}

// Scoped to compileJava only (never configureEach), so the isolated reobftest compile never picks up common's source
// too.
tasks.named<JavaCompile>("compileJava") {
    source(rootDir.resolve("../common/src/main/java"))
    // This compile source-merges the whole of common, and javac prints only the first hundred errors.
    options.compilerArgs.add("-Xmaxerrs")
    options.compilerArgs.add("100000")
}

// At the private member level, a {@link}, {@linkplain}, @see or @throws target the band lacks fails the island the way
// it fails the root.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        memberLevel = JavadocMemberLevel.PRIVATE
        addBooleanOption("Xdoclint:reference", true)
    }
}

if (band.getProperty("javadoc_gate").toBoolean()) {
    tasks.named("check") { dependsOn(tasks.named("javadoc")) }
}

tasks.named<ProcessResources>("processResources") {
    from(rootDir.resolve("../common/src/main/resources")) {
        exclude("**/.gitkeep")
    }
    val tokens = mapOf(
        "version" to version.toString(),
        "modrinth_id" to band("modrinth_id"),
        "mod_id" to band("mod_id"),
    )
    inputs.properties(tokens)
    filesMatching("mcmod.info") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

abstract class ConvertLangToProperties : DefaultTask() {
    @get:InputDirectory abstract val jsonDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    private fun shippedBaseName(code: String): String =
        if (!code.contains('_')) code
        else code.substringBefore('_') + "_" + code.substringAfter('_').uppercase(Locale.ROOT)

    @TaskAction fun convert() {
        val out = outDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        jsonDir.get().asFile.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { json ->
            @Suppress("UNCHECKED_CAST")
            val map = groovy.json.JsonSlurper().parse(json) as Map<String, Any?>
            val lang = out.resolve(shippedBaseName(json.nameWithoutExtension) + ".lang")
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
    description = "Converts the JSON lang catalog to 1.10.2 .lang (key=value, uppercase region) for the ship jar."
    jsonDir.set(layout.projectDirectory.dir("../common/src/main/resources/assets/wdl/lang"))
    outDir.set(layout.buildDirectory.dir("generated-lang/assets/wdl/lang"))
}

val reobfFixtureJar = tasks.register<Jar>("reobfFixtureJar") {
    group = "build"
    description = "Packages the reobftest fixture's class output for checkShipJar and checkReobfNegative to scan."
    archiveBaseName.set("reobftest-fixture")
    destinationDirectory.set(layout.buildDirectory.dir("reobf"))
    from(sourceSets["reobftest"].output)
}

// Classifying remapJar's output "searge" gives modJar a distinct file to read from. It also leaves build/libs, because
// both workflows select this island's jars by globbing that directory and skipping the classifiers they know about: an
// intermediate sitting beside modJar's output is published as if it were the mod.
tasks.named<Jar>("remapJar") {
    archiveClassifier.set("searge")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

// Unrelocated is only safe because nothing is duplicated. fastutilShipped below resolves the exact artifact this
// band's own Minecraft version manifest names, and only classes ABSENT from it are packaged, so no class in the
// ship jar shadows one the game already loads and no load order has to be reasoned about. The two are the same
// fastutil release, Mojang's a trimmed recompile of 7.0.12, so a bundled class links correctly against the
// game's own copies of its supertypes.
val fastutilBundleSource: Configuration = configurations.create("fastutilBundleSource") { isTransitive = false }
val fastutilShipped: Configuration = configurations.create("fastutilShipped") { isTransitive = false }

dependencies {
    fastutilBundleSource("it.unimi.dsi:fastutil:${band("fastutil_bundle_version")}@jar")
    fastutilShipped("it.unimi.dsi:fastutil:${band("fastutil_shipped_version")}@jar")
}

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

// The shipped Forge jar must be the reobf'd, searge-named artifact. Ship the license inside the jar: GPL-3.0 section 4,
// which LGPL-3.0 incorporates, asks that every recipient get a copy of the License with the Program, and a mod jar
// travels on its own far from any listing page (modpacks, mirrors, a copied mods/ folder).
val licenseTexts = listOf(rootDir.resolve("../LICENSE"), rootDir.resolve("../GPL-3.0.txt"))

val modJar = tasks.register<Jar>("modJar") {
    group = "build"
    description = "Assembles the shippable Forge mod jar: Unimined's native reobf'd (searge) classes + resources."
    dependsOn("remapJar")
    dependsOn(convertLang)
    archiveBaseName.set("${band("mod_archives_base")}-forge")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    // Legacy FML does not auto-discover accesstransformer.cfg by its META-INF path (that is the ModLauncher-era
    // convention); it reads the AT file named by the shipped jar's own FMLAT manifest attribute.
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
    from(bundleFastutil)
    // fastutil is Apache 2.0 and ships no license file of its own, so section 4(a)'s copy travels with the jar beside
    // WDL's own two.
    from(rootDir.resolve("../APACHE-2.0.txt")) {
        into("META-INF")
        rename { "APACHE-2.0-fastutil.txt" }
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

tasks.named("assemble") {
    dependsOn(modJar)
}

// The @Nullable's own descriptor is org.jspecify.*, never net/minecraft, so the load-bearing name is the annotated
// member's descriptor.
abstract class CheckSeargeSurface : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputFile
    abstract val seargeOracleFile: RegularFileProperty

    @get:InputFiles
    abstract val textFilesToScan: ConfigurableFileCollection

    @get:Input
    abstract val excludedEntryText: Property<String>

    // A wrong SRG v1 reader is the one failure in this area that no arm of the scan can report: an under-permissive
    // reader (the TSRG parser left in place, say) yields an empty oracle, which makes arm (b) and arm (c) flag
    // everything and reads as ordinary seam fallout. checkReobfNegative cannot distinguish that case, since it demands
    // an offender and a broken oracle manufactures one. Asserting the exact counts catches both directions, and it is
    // checked here rather than in a gate of its own so that every consumer of the oracle validates it, including the
    // inverted meta-test.
    @get:Input
    abstract val expectedOracleClasses: Property<Int>

    @get:Input
    abstract val expectedOracleMembers: Property<Int>

    @get:Input
    abstract val expectClean: Property<Boolean>

    init {
        excludedEntryText.convention("")
        expectClean.convention(true)
    }

    // A member of this task class rather than a script-level function: a script-level helper closes over the implicit
    // script instance, which turns a class that calls it into a non-static inner class Gradle's task instantiator
    // refuses to construct.
    private class SeargeOracle(val classes: Set<String>, val members: Set<String>)

    private fun parseSeargeOracle(srg: File): SeargeOracle {
        val classes = HashSet<String>()
        val members = HashSet<String>()
        // Records are keyed on the line's prefix, never on its token count: PK:, CL: and FD: lines all carry three
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

tasks.register("checkReobf") {
    group = "verification"
    description = "Alias onto checkShipJar's arm (b) positive reference scan; a wrong Minecraft reference cannot compile under Unimined."
    dependsOn(checkShipJar)
}

// Inverted meta-test (expectClean = false): scans the reobftest fixture unfiltered, where the bogus searge literal is a
// permanent, known offender, and passes when the scan finds it (proof arm (c) fires); it fails if the scan comes back
// clean, meaning the detector itself regressed. Belongs in check for exactly that reason: it is green whenever the gate
// works and red only on a real regression, the same as any other check.
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

// The Forge runtime floor and the mod id it hangs off are both written into WdlForge's @Mod(dependencies = ...) string,
// which is the one legacy FML actually enforces (FMLModContainer.bindMetadata takes dependencies off the annotation
// descriptor unless mcmod.info opts into useDependencyInformation, which this mod does not). An annotation value must
// be a compile-time constant, so neither half can read its counterpart and the duplication is structural.
//
// A floor off an earlier Forge line agrees with forge_version_min yet enforces nothing, and one off a later band's line
// loads for nobody, so the floor is also ordered against forge_version and pinned to its leading component. Forge's
// leading component tracks the Minecraft band, 12 here against 13 at 1.11.2 and 14 at 1.12.2, and nothing else in the
// build relates the two coordinates.
//
// The id Forge registers for itself at this band is Forge with a capital F, and FMLModContainer.sanityCheckModId warns
// that a modid should equal its lowercase form, which makes the capital look like the defect. That warning is about the
// id a mod declares for itself and never about a dependency label, so lowercasing this one is not a tidy but a total
// load failure: Loader.sortModList set-differences the declared names against the registered ids by String equality, so
// a mis-cased name resolves to nothing and the client aborts on MissingModsException before any mod runs.
val checkForgeFloor = tasks.register("checkForgeFloor") {
    group = "verification"
    description = "Fails if WdlForge's @Mod dependencies misses forge_version_min, outruns forge_version, or names a mod id Forge does not register"
    val forgeVersionMin = band("forge_version_min")
    val forgeVersion = band("forge_version")
    val entrypointSource = layout.projectDirectory
        .file("src/main/java/world/thearchive/wdl/forge/WdlForge.java")
    val provisionedForge = objects.fileCollection().from(configurations.named("minecraft"))
    inputs.property("forgeVersionMin", forgeVersionMin)
    inputs.property("forgeVersion", forgeVersion)
    inputs.file(entrypointSource)
    inputs.files(provisionedForge).withPropertyName("provisionedForge")
    doLast {
        fun numericParts(key: String, value: String): List<Int> =
            value.split('.').map { part ->
                part.toIntOrNull()
                    ?: throw GradleException(
                        "$key is \"$value\", which is not a dotted numeric Forge version, so the declared floor "
                            + "cannot be ordered against the build this island provisions"
                    )
            }
        val minParts = numericParts("forge_version_min", forgeVersionMin)
        val provisionedParts = numericParts("forge_version", forgeVersion)
        if (minParts.first() != provisionedParts.first()) {
            throw GradleException(
                "Forge line mismatch: forge_version_min is $forgeVersionMin but this island provisions Forge "
                    + "$forgeVersion. Forge's leading component tracks the Minecraft band, so a floor off another "
                    + "band's line is one no build of this band's line satisfies, and every user gets "
                    + "MissingModsException."
            )
        }
        val floorOrdering = (0 until maxOf(minParts.size, provisionedParts.size))
            .map { i -> minParts.getOrElse(i) { 0 }.compareTo(provisionedParts.getOrElse(i) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
        if (floorOrdering > 0) {
            throw GradleException(
                "Forge floor above the provisioned build: forge_version_min is $forgeVersionMin but this island "
                    + "provisions and ships against Forge $forgeVersion, so the jar demands a build newer than the "
                    + "one it was compiled against, and every user gets MissingModsException."
            )
        }
        // Raw file text, not the parsed annotation: a required-after entry inside a comment counts, and a second
        // Forge entry anywhere in this file reds the build rather than going unread.
        val forgeEntries = Regex("""required-after:([^@\s;]+)@\[([^,\]]+),""")
            .findAll(entrypointSource.asFile.readText())
            .filter { it.groupValues[1].equals("forge", ignoreCase = true) }
            .toList()
        if (forgeEntries.isEmpty()) {
            throw GradleException(
                "WdlForge declares no required-after Forge floor in its @Mod dependencies; legacy FML then "
                    + "enforces no Forge version at all"
            )
        }
        if (forgeEntries.size > 1) {
            throw GradleException(
                "WdlForge declares ${forgeEntries.size} required-after Forge floors in its @Mod dependencies "
                    + "(${forgeEntries.joinToString { it.value }}). Legacy FML enforces every entry it parses, so "
                    + "one this gate did not read can still stop the mod loading; declare exactly one."
            )
        }
        val declared = forgeEntries.single()
        val declaredId = declared.groupValues[1]
        val declaredFloor = declared.groupValues[2]
        if (declaredFloor != forgeVersionMin) {
            throw GradleException(
                "Forge floor mismatch: forge_version_min is $forgeVersionMin but WdlForge's @Mod dependencies "
                    + "declares $declaredFloor. The annotation is the one legacy FML enforces, so change both."
            )
        }
        val containerEntry = "net/minecraftforge/common/ForgeModContainer.class"
        val containerBytes = provisionedForge.files.asSequence()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .mapNotNull { jar ->
                ZipFile(jar).use { zip ->
                    zip.getEntry(containerEntry)?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
                }
            }
            .firstOrNull()
            ?: throw GradleException(
                "No $containerEntry in the provisioned Forge artifact, so the mod id Forge registers for itself "
                    + "cannot be read and the declared dependency cannot be checked against it"
            )
        var registeredId: String? = null
        ClassReader(containerBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    private var pending: String? = null

                    override fun visitLdcInsn(value: Any?) {
                        pending = value as? String
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, fieldDesc: String) {
                        if (opcode == Opcodes.PUTFIELD
                            && owner == "net/minecraftforge/fml/common/ModMetadata"
                            && fieldName == "modId"
                        ) {
                            registeredId = pending
                        }
                        pending = null
                    }
                }
            },
            0,
        )
        val registered = registeredId
            ?: throw GradleException(
                "$containerEntry assigns ModMetadata.modId from something other than a string constant, so the "
                    + "mod id Forge registers for itself can no longer be read out of it"
            )
        if (declaredId != registered) {
            throw GradleException(
                "Forge mod id mismatch: WdlForge's @Mod dependencies names \"$declaredId\" but ForgeModContainer "
                    + "registers \"$registered\". Loader.sortModList matches declared names against registered ids "
                    + "by String equality, so the mod does not load at all."
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

// Reads the finished ship jar and proves the fastutil bundle actually shipped. Every other gate on this island passes
// whether or not those classes are in the jar: checkShipJar scans for mapping leaks and searge-shaped literals, the
// compile resolves fastutil from the compileOnly jar either way, and the reobf pass has nothing to say about a
// third-party package. Without a check that opens the artifact, the defect is invisible until a real client throws
// NoClassDefFoundError.
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

// Vanilla is uppercase-region at this band: the client jar carries assets/minecraft/lang/en_US.lang, and 92 of the 93
// lang entries in asset index 1.10 are uppercase-region, the one exception being Mojang's own swg_de.
val shippedLangNames = listOf(
    "cs_CZ.lang", "da_DK.lang", "de_DE.lang", "en_PT.lang", "en_US.lang", "es_AR.lang", "es_CL.lang",
    "es_EC.lang", "es_ES.lang", "es_MX.lang", "es_UY.lang", "es_VE.lang", "esan.lang", "fi_FI.lang",
    "fr_CA.lang", "fr_FR.lang", "it_IT.lang", "ja_JP.lang", "ko_KR.lang", "nl_BE.lang", "nl_NL.lang",
    "nn_NO.lang", "no_NO.lang", "pl_PL.lang", "pt_BR.lang", "pt_PT.lang", "qcb_ES.lang", "ru_RU.lang",
    "sv_SE.lang", "tr_TR.lang", "uk_UA.lang", "zh_CN.lang", "zh_TW.lang",
)

// A mis-cased region never crashes. One mis-cased non-English name drops that locale to the English fallback. A
// mis-cased en_US is the fallback itself going missing, so English users see raw keys. The inherited all-lowercase form
// is every name at once.
abstract class CheckShippedLangNames : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:Input
    abstract val expectedNames: SetProperty<String>

    @TaskAction
    fun check() {
        val jar = jarToScan.get().asFile
        val dir = "assets/wdl/lang/"
        val shippedLang = sortedSetOf<String>()
        val shippedJson = sortedSetOf<String>()
        ZipInputStream(jar.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith(dir)) {
                    val base = entry.name.substring(dir.length)
                    if (base.endsWith(".lang")) shippedLang.add(base)
                    if (base.endsWith(".json")) shippedJson.add(base)
                }
                entry = zin.nextEntry
            }
        }
        val scanned = jar.name
        val expected = expectedNames.get().toSortedSet()
        val failures = ArrayList<String>()

        (expected - shippedLang).forEach { want ->
            val misCased = shippedLang.firstOrNull { it.equals(want, ignoreCase = true) }
            failures.add(
                if (misCased == null) "missing from $scanned: $dir$want"
                else "missing from $scanned: $dir$want ($scanned carries $misCased, which the game never asks for)"
            )
        }

        shippedJson.forEach {
            failures.add("$scanned carries the unreadable JSON source $dir$it; modJar's exclude is what drops it")
        }

        // Counted as well as matched: the missing check above is blind to a catalog the pinned list does not name.
        if (shippedLang.size != expected.size) {
            failures.add(
                "$scanned carries ${shippedLang.size} entries under $dir, expected ${expected.size}: "
                    + shippedLang.joinToString(", ")
            )
        }

        if (failures.isNotEmpty()) {
            throw GradleException("$name: ${failures.size} problem(s):\n" + failures.joinToString("\n"))
        }
        logger.lifecycle("$name: $scanned carries all ${expected.size} expected catalogs under $dir and no JSON")
    }
}

val checkShippedLangNames = tasks.register<CheckShippedLangNames>("checkShippedLangNames") {
    group = "verification"
    description = "Fails if the ship jar's lang catalog is not the expected uppercase-region .lang set."
    dependsOn(modJar)
    jarToScan.set(modJar.flatMap { it.archiveFile })
    expectedNames.set(shippedLangNames)
}

tasks.named("check") {
    dependsOn("checkReobf", checkShipJar, checkReobfNegative, checkForgeFloor, checkAcceptedMinecraftVersions,
        checkFastutilBundle, checkShippedLangNames)
}

// Coordinates come from band() (the island reads the root gradle.properties), not providers.gradleProperty, because
// forge/gradle.properties carries none. There is no github block: the release workflow funnels every band's jars into
// one shared GitHub release with gh.
publishMods {
    // Resolve the shipped jar by path: every island build system emits build/libs/archive-wdl-forge-<version>.jar, and
    // the release job builds the island before invoking publishMods, so one path form works on every band without a
    // build-system-specific task handle.
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
