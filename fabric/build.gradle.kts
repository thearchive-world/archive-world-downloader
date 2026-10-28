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

    // The mixins need Loom's mixin annotation processor: it emits the refmap that maps their targets from the Mojmap
    // compile names to the intermediary names the shipped jar runs against. Without this block no refmap ships, and the
    // mixins fail to apply on a real client with "No refMap loaded".
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("wdl.refmap.json")
    }
}

// This band's Minecraft version predates the Fabric client game test framework (fabric-client-gametest-api-v1, first
// shipped at 1.21.4), so the capture-verification tier is gated off here via client_gametest=false in
// gradle.properties.
val clientGameTest = providers.gradleProperty("client_gametest").map(String::toBoolean).getOrElse(true)

if (clientGameTest) {
    fabricApi {
        configureTests {
            createSourceSet = true
            modId = "wdl-gametest"
            enableGameTests = false
            enableClientGameTests = true
            eula = true
        }
    }
}

// configureEach so it applies whenever the clientGameTest run is registered.
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

if (clientGameTest) {
    tasks.named<JavaCompile>("compileGametestJava") {
        options.errorprone.enabled.set(false)
    }
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

repositories {
    maven("https://maven.parchmentmc.org")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

// Parchment param-name mappings are layered only on bands that publish them; a band with no Parchment release omits
// both properties and skips the parchment layer below.
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

    modCompileOnly("maven.modrinth:modmenu:${property("modmenu_version")}")

    compileOnly("info.journeymap:journeymap-api:${property("journeymap_api_coordinate")}-SNAPSHOT")

    // Guarded on clientGameTest: the gametestCompileOnly configuration exists only when the source set is created
    // above.
    if (clientGameTest) {
        "gametestCompileOnly"(libs.jspecify)
    }
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
