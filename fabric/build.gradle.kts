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
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${property("parchment_minecraft_version")}:${property("parchment_mappings_version")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

// Loom's remapJar is the producing task; the guard is shared with the NeoForge sibling (see build-logic).
registerVerifyProductionJar("remapJar")
