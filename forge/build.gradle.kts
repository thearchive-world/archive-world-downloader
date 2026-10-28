import net.minecraftforge.gradle.userdev.UserDevExtension
import java.util.Properties

buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:6.0.54")
    }
}

plugins {
    java
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

apply(plugin = "net.minecraftforge.gradle")

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

// reobfJar hands the jar task's output to ForgeAutoRenamingTool, which copies every entry's time into the reobfuscated
// jar it writes back over it, so the Jar defaults would put the build's wall clock into the shipped bytes and no two
// clean builds would match.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

configure<UserDevExtension> {
    mappings("official", band("minecraft_version"))
    // This file is auto-loaded at runtime from META-INF.
    accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))
}

dependencies {
    "minecraft"("net.minecraftforge:forge:${band("forge_version")}")

    compileOnly("org.jspecify:jspecify:1.0.0")

    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-SNAPSHOT")
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

// Coordinates come from band() (the island reads the root gradle.properties), not providers.gradleProperty. No github
// block: the release workflow funnels every band's jars into one shared GitHub release with gh.
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
