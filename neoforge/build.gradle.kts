import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("net.neoforged.moddev")     // version comes from the root apply-false declaration
    alias(libs.plugins.mod.publish.plugin)    // release upload; version from the root apply-false declaration
    id("com.diffplug.spotless")    // applied per-subproject, not via build-logic (see :common)
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("wdl.common-merge")
}

base {
    // -> archive-wdl-neoforge  (sibling to fabric's archive-wdl-fabric)
    archivesName.set("${property("mod_archives_base")}-neoforge")
}

// Spotless is applied per-subproject, not via build-logic: loaded from the included build, its
// Eclipse JDT formatter fails intermittently under the configuration cache + build cache + parallel
// execution, while from the root buildscript classpath it is stable. See :common for the full note.
spotless {
    java {
        target("src/**/*.java")
        eclipse("4.39").configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Parchment param-name mappings are layered only on bands that publish them; a band with no Parchment
// release (26.x) omits both properties and skips the parchment block below.
val parchmentMinecraft = providers.gradleProperty("parchment_minecraft_version")
val parchmentMappings = providers.gradleProperty("parchment_mappings_version")

neoForge {
    // NeoForge mode: set a NeoForge version (NOT a NeoForm version like common's Vanilla mode).
    // This puts NeoForge + Minecraft (Mojmap-named, no intermediary remap) on the classpath and
    // gives the client run type. The source-merge convention is provided by wdl.common-merge.
    version = property("neoforge_version") as String
    if (parchmentMinecraft.isPresent && parchmentMappings.isPresent) {
        parchment {
            minecraftVersion = parchmentMinecraft.get()
            mappingsVersion  = parchmentMappings.get()
        }
    }

    // The mod = the main source set, which the source-merge augments with :common's classes.
    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets["main"])
        }
    }

    // A dev client run (optional; the manual gate uses an external launcher).
    runs {
        create("client") {
            client()
        }
    }

    // --- per-loader smoke test on a production-faithful FML module load ---
    // MDG NeoForge-mode's unitTest{} block boots FMLLoader (the SAME mod-loading path the client
    // uses) and loads the test/ classes AS the mod. This is strictly stronger than a plain-classpath
    // test: it reproduces the real JPMS module setup, so it catches mod-LOAD failures a flat-classpath
    // test cannot: it catches the automatic-module uses crash that a services array in
    // neoforge.mods.toml triggers. ServiceLoader resolution is then exercised
    // against the loaded mod's own classloader.
    unitTest {
        enable()
        testedMod = mods[property("mod_id") as String]
    }
}

tasks.named<ProcessResources>("processResources") {
    // Keep neoforge.mods.toml's templated fields in sync with gradle.properties: the mod version, the
    // MC pin, and the NeoForge floor (neoforge_version_min, a deliberate value distinct from the build
    // coordinate neoforge_version). The :common resource merge and the .gitkeep exclude are handled by
    // wdl.common-merge.
    val tokens = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "neoforge_version_min" to project.property("neoforge_version_min").toString(),
        "modrinth_id" to project.property("modrinth_id").toString(),
        "mod_id" to project.property("mod_id").toString(),
    )
    inputs.properties(tokens)
    filesMatching("META-INF/neoforge.mods.toml") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// The plain jar is the producing task; the guard is shared with the Fabric sibling (see build-logic).
registerVerifyProductionJar("jar")

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

dependencies {
    // XaeroPlus public API for the source-merged overlay binding (compat/xaeroplus), compile-only. NeoForge is
    // ModDev (no remap), neoforge flavor, no runtime require. XaeroPlus hard-depends on xaeroworldmap, so the
    // whole Xaero family stays out of the build; the manual render gate installs it in a real client.
    compileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+neoforge-${property("minecraft_version")}")

    // JourneyMap public API for the source-merged overlay binding (compat/journeymap), compile-only. NeoForge
    // is ModDev (no remap), neoforge flavor, no runtime require.
    compileOnly("info.journeymap:journeymap-api:${property("journeymap_api_coordinate")}-neoforge-SNAPSHOT")
}

// Release publishing (mod-publish-plugin): uploads the NeoForge jar to CurseForge and Modrinth per this band's
// MC version. There is no github block, because the plugin's parent() link is a same-build dependency and
// cannot reach a release another band's separate run created, so the release workflow creates the shared
// release with gh. Nothing publishes on an ordinary build.
publishMods {
    file.set(tasks.jar.flatMap { it.archiveFile })
    changelog.set(providers.environmentVariable("CHANGELOG").orElse(""))
    type.set(STABLE)
    version.set(project.version.toString())
    displayName.set("${project.version} (NeoForge)")
    modLoaders.add("neoforge")
    dryRun.set(
        providers.environmentVariable("PUBLISH_DRY_RUN").map { it.toBooleanStrict() }.orElse(false),
    )

    modrinth {
        projectId.set(providers.gradleProperty("modrinth_id"))
        accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
        minecraftVersions.add(providers.gradleProperty("minecraft_version"))
        // Java mod: no Kotlin-for-Forge (or any other) hard runtime dependency to declare.
    }

    curseforge {
        projectId.set(providers.gradleProperty("curseforge_id"))
        accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
        minecraftVersions.add(providers.gradleProperty("minecraft_version"))
        // Client-only mod: CurseForge requires at least one declared environment.
        client.set(true)
        server.set(false)
    }
}
