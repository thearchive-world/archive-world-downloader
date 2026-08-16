import net.minecraftforge.gradle.userdev.UserDevExtension
import java.util.Properties

// The non-Fabric jar for this deep band, built on the ForgeGradle 6 toolchain against real Forge. FG6 is
// Gradle-8-only, so this is a separate Gradle-8 island beside the band's Gradle-9 root (common + fabric); the
// two builds have two wrappers. ForgeGradle publishes no plugin marker, so it is applied from the buildscript
// classpath rather than the plugins block.
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
}

apply(plugin = "net.minecraftforge.gradle")

// Single source of coordinates: the island reads the band's gradle.properties from the sibling Gradle-9 root
// rather than duplicating the version, MC pin, and compat coordinates. Two wrappers, one set of coordinates.
val band = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun band(key: String): String = band.getProperty(key) ?: error("missing '$key' in ../gradle.properties")

group = band("mod_group")
// Match wdl.java-conventions: the MC patch rides as SemVer build metadata, e.g. 1.1.0+1.20.2.
version = "${band("mod_version")}+${band("minecraft_version")}"

base {
    // -> archive-wdl-forge  (the Fabric sibling is archive-wdl-fabric)
    archivesName.set("${band("mod_archives_base")}-forge")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(band("java_version").toInt())) }
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

configure<UserDevExtension> {
    // Forge's Mojang-official mappings put Mojmap net.minecraft on the classpath directly, the exact namespace
    // common/ is written in, with no intermediary remap layer.
    mappings("official", band("minecraft_version"))
    // Widen at compile the three read-only vanilla fields the tee and the mount-menu read reach, the Forge
    // analog of the Fabric access widener; the same file is auto-loaded at runtime from META-INF.
    accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))
}

dependencies {
    "minecraft"("net.minecraftforge:forge:${band("forge_version")}")

    // JSpecify (@NullMarked / @Nullable), compile-only and CLASS-retention: the source-merged common/ and the
    // shim are null-marked. NullAway itself does not run on this island (it is a Gradle-9 build-logic pass over
    // common + fabric); here the annotations only need to resolve so the marked source compiles.
    compileOnly("org.jspecify:jspecify:1.0.0")

    // Compat overlay APIs for the source-merged bindings (compat/xaeroplus, compat/journeymap), compile-only,
    // never a runtime require. FG6 compiles against Mojmap (official mappings), so these come from the NeoForge
    // (Mojmapped) flavor that :common already pulls, not the SRG-named Forge flavor: the Mojmap flavor matches
    // this classpath, and reobfJar maps WDL's calls to SRG for the shipped Forge jar.
    compileOnly("maven.modrinth:xaeroplus:${band("xaeroplus_version")}+neoforge-${band("minecraft_version")}")
    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-neoforge-SNAPSHOT")
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
