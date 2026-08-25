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
    // Release publishing. Pinned as a literal because the island is a separate build with no access to the root
    // version catalog; keep in sync with gradle/libs.versions.toml's mod-publish-plugin.
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

apply(plugin = "net.minecraftforge.gradle")

// Single source of coordinates: the island reads the band's gradle.properties from the sibling Gradle-9 root
// rather than duplicating the version, MC pin, and compat coordinates. Two wrappers, one set of coordinates.
val band = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun band(key: String): String = band.getProperty(key) ?: error("missing '$key' in ../gradle.properties")

group = band("mod_group")
// Match wdl.java-conventions: the MC patch rides as SemVer build metadata, e.g. 1.1.0+1.20.1.
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
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
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
    // never a runtime require. FG6 compiles against Mojmap (official mappings). At this band there is no
    // +neoforge XaeroPlus file or -neoforge-SNAPSHOT JourneyMap flavor, so these use the same coordinates :common
    // resolves: XaeroPlus's +forge file (which declares both the forge and neoforge loaders) and the
    // loader-suffixless JourneyMap 1.9 API. reobfJar maps WDL's calls to SRG for the shipped Forge jar.
    compileOnly("maven.modrinth:xaeroplus:${band("xaeroplus_version")}+forge-${band("minecraft_version")}")
    // Both JourneyMap generations, matching :common: the loader-suffixless 1.9 API for the 5.x plugin, and the
    // 2.0 API's -forge flavor for the 6.x plugin in compat/journeymap/v2.
    compileOnly("info.journeymap:journeymap-api:${band("journeymap_api_coordinate")}-SNAPSHOT")
    compileOnly("info.journeymap:journeymap-api-forge:${band("journeymap_api_v2_coordinate")}")
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

// Release publishing (mod-publish-plugin), driven by the release workflow on a version tag: it uploads the Forge
// jar to CurseForge and Modrinth per this band's MC version, mirroring the fabric/neoforge loader subprojects.
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
