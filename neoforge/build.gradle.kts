import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("net.neoforged.moddev")
    alias(libs.plugins.mod.publish.plugin)
    id("com.diffplug.spotless")
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("wdl.common-merge")
}

base {
    archivesName.set("${property("mod_archives_base")}-neoforge")
}

// Spotless is applied per-subproject, not via build-logic: loaded from the included build, its Eclipse JDT formatter
// fails intermittently under the configuration cache + build cache + parallel execution, while from the root
// buildscript classpath it is stable.
spotless {
    java {
        target("src/**/*.java")
        eclipse("4.40").configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val parchmentMinecraft = providers.gradleProperty("parchment_minecraft_version")
val parchmentMappings = providers.gradleProperty("parchment_mappings_version")

neoForge {
    version = property("neoforge_version") as String
    if (parchmentMinecraft.isPresent && parchmentMappings.isPresent) {
        parchment {
            minecraftVersion = parchmentMinecraft.get()
            mappingsVersion  = parchmentMappings.get()
        }
    }

    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        create("client") {
            client()
        }
    }

    unitTest {
        enable()
        testedMod = mods[property("mod_id") as String]
    }
}

tasks.named<ProcessResources>("processResources") {
    // Keep neoforge.mods.toml's templated fields in sync with gradle.properties: the mod version, the MC pin, and the
    // NeoForge floor (neoforge_version_min, a deliberate value distinct from the build coordinate neoforge_version).
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

registerVerifyProductionJar("jar")

repositories {
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

dependencies {
    // XaeroPlus hard-depends on xaeroworldmap, so the whole Xaero family is kept off every run.
    compileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+neoforge-${property("minecraft_version")}")

    compileOnly("info.journeymap:journeymap-api-neoforge:${property("journeymap_api_coordinate")}")
}

// There is no github block, because the plugin's parent() link is a same-build dependency and cannot reach a release
// another band's separate run created.
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
