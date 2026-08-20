import java.util.Properties

// The non-Fabric jar for this deep band. ForgeGradle 6 cannot build it at 1.16.5: below the 1.17 Great Rename it
// serves the frozen mcp_config SRG package layout (net.minecraft.network.play.server) and its official channel
// overlays only member names, so the mod's Mojang-official source (net.minecraft.network.protocol.game) resolves
// to nothing. This island builds on Architectury Loom instead, which puts officialMojangMappings on the dev
// classpath, the exact namespace common/ and the Fabric island compile against, and reobfuscates the jar to SRG
// for the Forge runtime. The island stays a separate build beside the band's Gradle-9 root (common + fabric)
// because it runs a different Loom fork than the root's Fabric Loom and carries a build-wide loom.platform=forge
// switch, not because of any Gradle-version limit; two wrappers, one set of coordinates read from the root
// gradle.properties.
plugins {
    id("dev.architectury.loom") version "1.14.476"
}

// Single source of coordinates: the island reads the band's gradle.properties from the sibling Gradle-9 root
// rather than duplicating the version, MC pin, and compat coordinates. Two wrappers, one set of coordinates.
val band = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun band(key: String): String = band.getProperty(key) ?: error("missing '$key' in ../gradle.properties")

group = band("mod_group")
// Match wdl.java-conventions: the MC patch rides as SemVer build metadata, e.g. 1.1.0+1.16.5.
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
    maven("https://maven.minecraftforge.net/")
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

loom {
    silentMojangMappingsLicense()
    forge {
        // Widen at compile the read-only vanilla fields the tee and the mount-menu bind reach, the Forge analog of
        // the Fabric access widener. The file is the classic Forge 1.16.x form (MCP class names, SRG member ids):
        // Loom remaps it onto the named dev classpath and ships it verbatim, and Forge auto-loads it at runtime
        // from META-INF/accesstransformer.cfg where the vanilla classes are SRG-named.
        accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${band("minecraft_version")}")
    // Mojang-official names on the dev classpath, the exact namespace common/ is written in, with no intermediary
    // remap layer. remapJar reobfuscates the compiled output, including the source-merged common, to SRG.
    mappings(loom.officialMojangMappings())
    // The forge configuration has no generated Kotlin DSL accessor, so it is invoked by name.
    "forge"("net.minecraftforge:forge:${band("forge_version")}")

    // JSpecify (@NullMarked / @Nullable), compile-only and CLASS-retention: the source-merged common/ and the
    // shim are null-marked. NullAway itself does not run on this island (it is a Gradle-9 build-logic pass over
    // common + fabric); here the annotations only need to resolve so the marked source compiles.
    compileOnly("org.jspecify:jspecify:1.0.0")

    // JourneyMap public API for the source-merged binding (compat/journeymap), compile-only, never a runtime
    // require. The 1.15.2 JourneyMap (5.7.0) is Forge-only and bundles the 1.8 API generation
    // (journeymap.client.api); the island compiles under officialMojangMappings, matching the plain
    // loader-suffixless 1.8 jar :common resolves, and remapJar maps WDL's calls to SRG for the shipped jar.
    // JourneyMap discovers the plugin here by annotation scan. No XaeroPlus binding on this band: XaeroPlus ships
    // no 1.15.x build, so the overlay is dropped as a disclosed limit, matching :common.
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
