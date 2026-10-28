import java.util.Properties
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
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
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

// This configuration pins the same underlying artifact Unimined's searge() mapping itself resolves,
// de.oceanlabs.mcp:mcp_config for this band's exact Minecraft version.
val seargeOracle: Configuration = configurations.create("seargeOracle") { isTransitive = false }

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

sourceSets {
    create("reobftest")
}

val jspecify = "org.jspecify:jspecify:1.0.0"

dependencies {
    compileOnly(jspecify)

    compileOnly("info.journeymap:journeymap-api-forge:${band("journeymap_api_v2_coordinate")}")
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

// The shipped Forge jar must be the reobf'd, searge-named artifact. The one resource this island packages differently
// from processResources' own output is the lang directory: MC 1.12.2 loads .lang, so the source lang JSON is excluded
// here and convertLang's converted .lang output takes its place. Ship the license inside the jar: GPL-3.0 section 4,
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
    dependsOn(modJar)
    jarToScan.set(modJar.flatMap { it.archiveFile })
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedTsrg })
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
    seargeOracleFile.set(extractSeargeOracle.flatMap { it.joinedTsrg })
    expectClean.set(false)
}

// The Forge runtime floor is written twice and no compiler sees both: forge_version_min in ../gradle.properties is
// the band coordinate, and WdlForge's @Mod(dependencies = ...) is the string legacy FML actually enforces
// (FMLModContainer.bindMetadata takes dependencies off the annotation descriptor unless mcmod.info opts into
// useDependencyInformation, which this mod does not). An annotation value must be a compile-time constant, so the
// literal cannot read the property and the duplication is structural. Without this gate a bumped property changes
// nothing the loader sees, silently.
//
// A floor off an earlier band's Forge line agrees with forge_version_min yet enforces nothing, and one off a later line
// loads for nobody, so the floor is also ordered against forge_version, the build this island provisions, and pinned to
// its leading component. Forge's leading component tracks the Minecraft band, 14 here against 12 at 1.10.2 and 13 at
// 1.11.2, and nothing else in the build relates the two coordinates.
val checkForgeFloor = tasks.register("checkForgeFloor") {
    group = "verification"
    description = "Fails if WdlForge's @Mod dependencies floor does not match forge_version_min, outruns forge_version, or names a mod id Forge does not register"
    val forgeVersionMin = band("forge_version_min")
    val forgeVersion = band("forge_version")
    val entrypointSource = layout.projectDirectory
        .file("src/main/java/world/thearchive/wdl/forge/WdlForge.java")
    inputs.property("forgeVersionMin", forgeVersionMin)
    inputs.property("forgeVersion", forgeVersion)
    inputs.file(entrypointSource)
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
        val entry = forgeEntries.single()
        val declaredId = entry.groupValues[1]
        val declared = entry.groupValues[2]
        if (declared != forgeVersionMin) {
            throw GradleException(
                "Forge floor mismatch: forge_version_min is $forgeVersionMin but WdlForge's @Mod dependencies "
                    + "declares $declared. The annotation is the one legacy FML enforces, so change both."
            )
        }
        // ForgeModContainer registers the literal "forge" at this band and the literal "Forge" at 1.10.2, so this
        // string is band-local, not a tidy.
        if (declaredId != "forge") {
            throw GradleException(
                "Forge mod id mismatch: WdlForge's @Mod dependencies names \"$declaredId\" but ForgeModContainer "
                    + "registers \"forge\" at this band. Loader.sortModList matches declared names against "
                    + "registered ids by String equality, so the mod does not load at all."
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkReobf", checkShipJar, checkReobfNegative, checkForgeFloor)
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
