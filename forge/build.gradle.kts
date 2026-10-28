import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.10.1")
    }
}

// The non-Fabric jar for this deep band. ForgeGradle 6 cannot build it at 1.16.5: below the 1.17 Great Rename it serves
// the frozen mcp_config SRG package layout (net.minecraft.network.play.server) and its official channel overlays only
// member names, so the mod's Mojang-official source (net.minecraft.network.protocol.game) resolves to nothing. The
// island stays a separate build beside the band's Gradle-9 root (common + fabric) because it runs a different Loom fork
// than the root's Fabric Loom.
plugins {
    id("dev.architectury.loom") version "1.17.493"
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
    maven("https://maven.minecraftforge.net/")
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

loom {
    silentMojangMappingsLicense()
    forge {
        accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))
    }
}

val jspecify = "org.jspecify:jspecify:1.0.0"

dependencies {
    minecraft("com.mojang:minecraft:${band("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    "forge"("net.minecraftforge:forge:${band("forge_version")}")

    compileOnly(jspecify)

    // The island compiles under officialMojangMappings, so it takes the -common flavor, not -forge: at this band the
    // -forge flavor carries MCP names that would not resolve, while -common carries the official names the merged
    // source uses.
    compileOnly("info.journeymap:journeymap-api-common:${band("journeymap_api_v2_coordinate")}")
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
