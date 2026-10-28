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

buildscript {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
    dependencies {
        classpath("net.fabricmc:tiny-remapper:0.14.1")
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-commons:9.10.1")
    }
}

plugins {
    java
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
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
    maven("https://maven.minecraftforge.net/")
}

val islandClasspathFile = rootDir.resolve("../common/build/island-classpath.txt")
require(islandClasspathFile.exists()) {
    "island classpath not found at $islandClasspathFile; run ':common:writeIslandClasspath' in the root build first"
}
val islandMinecraft = files(islandClasspathFile.readLines().filter { it.isNotBlank() })

val mcJarMarker = "/stripped-minecraft/"
val strippedMinecraftJar = islandMinecraft.filter { it.path.replace('\\', '/').contains(mcJarMarker) }
val islandLibraries = islandMinecraft.filter { !it.path.replace('\\', '/').contains(mcJarMarker) }

sourceSets {
    create("reobftest")
}

val forgeUniversal: Configuration = configurations.create("forgeUniversal") { isTransitive = false }

val forgeApiMappingFile = rootDir.resolve("../tools/mojmap-bridge/build/bridge/mojmap-srg.tiny")
require(forgeApiMappingFile.exists()) {
    "bridge mapping not found at $forgeApiMappingFile; run ':genBridge' in tools/mojmap-bridge first"
}

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
                        copy.time = 0L
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
                    copy.time = 0L
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

val jspecify = "org.jspecify:jspecify:1.0.0"

dependencies {
    forgeUniversal("net.minecraftforge:forge:${band("forge_version")}:universal")

    "reobftestCompileOnly"(islandMinecraft)

    compileOnly(islandLibraries)
    compileOnly(files(widenForCompile.flatMap { it.widenedJar }))
    compileOnly(files(remapForgeApi.flatMap { it.forgeApiJar }))
    compileOnly("net.minecraftforge:eventbus:0.9.3")
    compileOnly("net.minecraftforge:forgespi:0.13.0")
    compileOnly("org.apache.maven:maven-artifact:3.6.0")

    compileOnly(jspecify)

    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-SNAPSHOT")
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

tasks.named<JavaCompile>("compileJava") {
    source(rootDir.resolve("../common/src/main/java"))
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
        "minecraft_version" to band("minecraft_version"),
        "forge_version_min" to band("forge_version_min"),
        "modrinth_id" to band("modrinth_id"),
        "mod_id" to band("mod_id"),
    )
    inputs.properties(tokens)
    filesMatching("META-INF/mods.toml") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

val reobfMappingFile = rootDir.resolve("../tools/mojmap-bridge/build/bridge/mojmap-srg.tiny")
val srgOracleFile = rootDir.resolve("../tools/mojmap-bridge/build/sources/joined_1132.tsrg")
require(reobfMappingFile.exists()) {
    "reobf mapping not found at $reobfMappingFile; run ':genBridge' in tools/mojmap-bridge first"
}
require(srgOracleFile.exists()) {
    "1.13.2 SRG oracle (joined.tsrg) not found at $srgOracleFile; run ':genBridge' in tools/mojmap-bridge first"
}

// The Minecraft classpath is read (not emitted) so tiny-remapper can resolve the reference hierarchy.
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
                        entry.time = 0L
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

// The blaze3d package DOES exist at 1.13.2 (e.g. com/mojang/blaze3d/platform/GlStateManager), but every blaze3d class
// reobfuscates to a net/minecraft SRG name (mojmap-srg maps them all to net/minecraft/*), so a correctly reobfuscated
// 1.13.2 jar carries zero blaze3d references; a survivor is an unmapped leak, the same failure class as a leaked Mojmap
// net/minecraft name. A non-searge name is NOT a leak when it resolves to a member inherited from a JDK or library
// supertype (java.lang.Enum.ordinal, a JDK-collection addAll): such names are un-obfuscatable and correct at runtime.
// The owner is reverse-mapped srg -> mojmap and its supertypes are walked over the Mojmap Minecraft jar; a mapped
// owner's member is a leak only if some class in that hierarchy actually declares it (a method by name and
// srg-normalized descriptor, a field by name).
abstract class CheckReobf : DefaultTask() {
    @get:InputFile
    abstract val jarToScan: RegularFileProperty

    @get:InputFile
    abstract val srgClassSource: RegularFileProperty

    @get:InputFile
    abstract val mojmapSrgMapping: RegularFileProperty

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
            // A method descriptor starts with '(' (memberRefs mixes methods and fields); a field carries a plain type
            // descriptor.
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

val modJar = tasks.register<Jar>("modJar") {
    group = "build"
    description = "Assembles the shippable Forge mod jar: reobf'd SRG classes + resources + accesstransformer.cfg."
    dependsOn(reobfJar)
    archiveBaseName.set("${band("mod_archives_base")}-forge")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    from(zipTree(reobfJar.flatMap { it.srgJar }))
    from(tasks.named("processResources"))
}

// The default jar carries a dev classifier so it never collides with modJar's ship name.
tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

tasks.named("assemble") {
    dependsOn(modJar)
}

// The @Nullable's own descriptor is org.jspecify.*, never net/minecraft, so the load-bearing name is the annotated
// member's descriptor.
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
