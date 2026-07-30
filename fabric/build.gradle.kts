import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("fabric-loom")     // version comes from the root apply-false declaration
    id("com.diffplug.spotless")    // applied per-subproject, not via build-logic (see :common)
    id("wdl.java-conventions")
    id("wdl.nullness-conventions")
    id("wdl.common-merge")
}

base {
    // -> archive-wdl-fabric  (the NeoForge subproject will set archive-wdl-neoforge)
    archivesName.set("${property("mod_archives_base")}-fabric")
}

loom {
    // Compile-time widening for the inbound entity tee: read access on the private, non-final
    // Connection.channel. Runtime application is declared in fabric.mod.json; both point at the same file.
    accessWidenerPath.set(file("src/main/resources/wdl.accesswidener"))
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

repositories {
    maven("https://maven.parchmentmc.org")   // Loom layered Parchment mappings live here
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.blamejared.com") { content { includeGroup("info.journeymap") } }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${property("parchment_minecraft_version")}:${property("parchment_mappings_version")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // XaeroPlus public API for the source-merged overlay binding (compat/xaeroplus), compile-only. Loom remaps
    // the fabric flavor to Mojmap for the compile; there is no runtime require. XaeroPlus hard-depends on
    // xaeroworldmap, so the whole Xaero family is kept off every run and the headless gametest never hits
    // Xaero's startup update modal; the manual render gate installs the family in a real client.
    modCompileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+fabric-${property("minecraft_version")}")

    // JourneyMap API for the source-merged overlay binding (compat/journeymap) and the journeymap entrypoint
    // (WdlJourneyMapPlugin), compile-only. Loom remaps it to Mojmap for the compile; there is no runtime
    // require, so JourneyMap's absence just means the entrypoint is never queried.
    modCompileOnly("info.journeymap:journeymap-api-fabric:${property("journeymap_api_version")}-${property("minecraft_version")}")
}

tasks.named<ProcessResources>("processResources") {
    // Keep fabric.mod.json's templated fields in sync with gradle.properties: the mod version, the MC
    // pin, the Java floor (= the toolchain language version, i.e. bytecode target == minimum JRE), and
    // the loader floor (fabric_loader_version_min, a deliberate value distinct from the build coordinate
    // fabric_loader_version). The :common resource merge and the .gitkeep exclude are handled by wdl.common-merge.
    val tokens = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "java_version" to project.property("java_version").toString(),
        "fabric_loader_version_min" to project.property("fabric_loader_version_min").toString(),
        "mod_id" to project.property("mod_id").toString(),
    )
    inputs.properties(tokens)
    filesMatching("fabric.mod.json") { expand(tokens) }
}

// Loom's remapJar is the producing task; the guard is shared with the NeoForge sibling (see build-logic).
registerVerifyProductionJar("remapJar")
