import net.ltgt.gradle.errorprone.errorprone
import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("fabric-loom")
    alias(libs.plugins.mod.publish.plugin)
    id("com.diffplug.spotless")
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("wdl.common-merge")
}

base {
    archivesName.set("${property("mod_archives_base")}-fabric")
}

loom {
    // Runtime application is declared in fabric.mod.json; both point at the same file.
    accessWidenerPath.set(file("src/main/resources/wdl.accesswidener"))
}

// Server game tests are not used here, so only the client tests are enabled. eula accepts the Minecraft EULA: the tier
// connects the client to an in-process DEDICATED server over a real connection, and createServer() refuses to start
// until the EULA is accepted.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "wdl-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// WDL inserts a pass-through handler into the Netty pipeline on join (the entity tee), which the client gametest
// network synchronizer cannot account for; the official remedy is to disable it (see
// https://docs.fabricmc.net/develop/automatic-testing).
loom {
    runs {
        configureEach {
            if (name == "clientGameTest") {
                @Suppress("DEPRECATION")
                vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
            }
        }
    }
}

// The Error Prone plugin otherwise attaches to every JavaCompile, and unconfigured NullAway aborts the gametest
// compile.
tasks.named<JavaCompile>("compileGametestJava") {
    options.errorprone.enabled.set(false)
}

// The Loom ClientProductionRunTask + useXVFB recipe drives the shipped production jar, which does not contain this
// tier's gametest source set (createSourceSet puts it in a separate mod that the production run never loads), so it
// boots and exits without running any test.

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

repositories {
    maven("https://maven.parchmentmc.org")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

val parchmentMinecraft = providers.gradleProperty("parchment_minecraft_version")
val parchmentMappings = providers.gradleProperty("parchment_mappings_version")

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        if (parchmentMinecraft.isPresent && parchmentMappings.isPresent) {
            parchment("org.parchmentmc.data:parchment-${parchmentMinecraft.get()}:${parchmentMappings.get()}@zip")
        }
    })
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // XaeroPlus hard-depends on xaeroworldmap, so the whole Xaero family is kept off every run.
    modCompileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+fabric-${property("minecraft_version")}")

    modCompileOnly("maven.modrinth:modmenu:${property("modmenu_version")}")

    modCompileOnly("info.journeymap:journeymap-api-fabric:${property("journeymap_api_coordinate")}")

    "gametestCompileOnly"(libs.jspecify)
}

tasks.named<ProcessResources>("processResources") {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "java_version" to project.property("java_version").toString(),
        "fabric_loader_version_min" to project.property("fabric_loader_version_min").toString(),
        "modrinth_id" to project.property("modrinth_id").toString(),
        "mod_id" to project.property("mod_id").toString(),
    )
    inputs.properties(tokens)
    filesMatching("fabric.mod.json") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// Loom's remapJar is the producing task.
registerVerifyProductionJar("remapJar")

// Captured into a val for configuration-cache compatibility (no Project access inside the task action).
tasks.register("printVersion") {
    val projectVersion = version.toString()
    doLast { println("PROJECT_VERSION=$projectVersion") }
}

// There is no github block, because the plugin cannot carry one GitHub release across the separate per-band runs.
publishMods {
    file.set(tasks.remapJar.flatMap { it.archiveFile })
    changelog.set(providers.environmentVariable("CHANGELOG").orElse(""))
    type.set(STABLE)
    version.set(project.version.toString())
    displayName.set("${project.version} (Fabric)")
    modLoaders.add("fabric")
    modLoaders.add("quilt")
    dryRun.set(
        providers.environmentVariable("PUBLISH_DRY_RUN").map { it.toBooleanStrict() }.orElse(false),
    )

    modrinth {
        projectId.set(providers.gradleProperty("modrinth_id"))
        accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
        minecraftVersions.add(providers.gradleProperty("minecraft_version"))
        requires { id.set("P7dR8mSH") }
    }

    curseforge {
        projectId.set(providers.gradleProperty("curseforge_id"))
        accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
        minecraftVersions.add(providers.gradleProperty("minecraft_version"))
        // Client-only mod: CurseForge requires at least one declared environment.
        client.set(true)
        server.set(false)
        requires("fabric-api")
    }
}
