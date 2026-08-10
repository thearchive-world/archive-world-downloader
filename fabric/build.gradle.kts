import net.ltgt.gradle.errorprone.errorprone
import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("net.fabricmc.fabric-loom")     // no-remap loom: MC 26.x ships unobfuscated, so nothing is remapped
    alias(libs.plugins.mod.publish.plugin)    // release upload; version from the root apply-false declaration
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

// Automated capture-verification tier: the official Fabric Client Game Test API boots a real client
// plus an in-process server and hands the test a real ClientLevel, so the live capture front (the chunk
// snapshot reading a ClientLevel and the inbound entity Netty tee) runs headlessly. createSourceSet puts
// the harness in its own gametest source set, off the fast :common:test / build gate. Server game tests
// are not used here (the tier is client-side), so only the client tests are enabled. eula accepts the
// Minecraft EULA so the harness may write eula.txt: the tier connects the client to an in-process
// DEDICATED server over a real connection, and createServer() refuses to start until the EULA is
// accepted. The maintainer has read and accepted https://aka.ms/MinecraftEULA.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "wdl-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// WDL inserts a pass-through inbound handler into the Netty pipeline on join (the entity tee), which the
// client gametest network synchronizer cannot account for; the official remedy is to disable it (see
// https://docs.fabricmc.net/develop/automatic-testing). configureEach so it applies whenever the
// clientGameTest run is registered, independent of block order. vmArg is the working run-config VM-arg
// setter on this Loom; its lazy successors throw on mutation, so the deprecation note stands.
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

// The gametest source set is test-scope, so Error Prone / NullAway is off here, mirroring how
// wdl.nullness-conventions disables it for compileTestJava (NullAway is production-only). The Error Prone
// plugin otherwise attaches to every JavaCompile, and unconfigured NullAway aborts the gametest compile.
tasks.named<JavaCompile>("compileGametestJava") {
    options.errorprone.enabled.set(false)
}

// Headless CI runs the dev clientGameTest run (runClientGameTest) under a virtual framebuffer (xvfb-run).
// The Loom ClientProductionRunTask + useXVFB recipe was evaluated and rejected here: it
// drives the shipped production jar, which does not contain this tier's gametest source set (createSourceSet
// puts it in a separate mod that the production run never loads), so it boots and exits without running any
// test. The dev run loads the gametest source set, and xvfb-run supplies the same virtual GL context, so the
// CI workflow wraps runClientGameTest in xvfb-run instead. This stays off check/build, the fast gate.

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

// Parchment param-name mappings are layered only on bands that publish them; a band with no Parchment
// release (26.x) omits both properties and skips the parchment layer below.
val parchmentMinecraft = providers.gradleProperty("parchment_minecraft_version")
val parchmentMappings = providers.gradleProperty("parchment_mappings_version")

dependencies {
    "minecraft"("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // XaeroPlus public API for the source-merged overlay binding (compat/xaeroplus), compile-only. Loom remaps
    // the fabric flavor to Mojmap for the compile; there is no runtime require. XaeroPlus hard-depends on
    // xaeroworldmap, so the whole Xaero family is kept off every run and the headless gametest never hits
    // Xaero's startup update modal; the manual render gate installs the family in a real client.
    compileOnly("maven.modrinth:xaeroplus:${property("xaeroplus_version")}+fabric-${property("minecraft_version")}")

    // ModMenu API for the mod-list config-screen entrypoint (WdlModMenu), compile-only. Loom remaps it to
    // Mojmap for the compile; there is no runtime require, so ModMenu's absence just means the entrypoint is
    // never queried. The pinned version supplies only the stable ModMenuApi; the player's own ModMenu runs.
    compileOnly("maven.modrinth:modmenu:${property("modmenu_version")}")

    // JourneyMap API for the source-merged overlay binding (compat/journeymap) and the journeymap entrypoint
    // (WdlJourneyMapPlugin), compile-only. Loom remaps it to Mojmap for the compile; there is no runtime
    // require, so JourneyMap's absence just means the entrypoint is never queried.
    compileOnly("info.journeymap:journeymap-api-fabric:${property("journeymap_api_coordinate")}")

    // JSpecify on the gametest source set so its package-info @NullMarked resolves; compileOnly is not
    // transitive across source sets. NullAway does not run here (test-scope, disabled above), so the marking
    // is documentary only, but it keeps the tree visibly consistent with main and test.
    "gametestCompileOnly"(libs.jspecify)
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
        "modrinth_id" to project.property("modrinth_id").toString(),
        "mod_id" to project.property("mod_id").toString(),
    )
    inputs.properties(tokens)
    filesMatching("fabric.mod.json") { expand(tokens) }
    filesMatching("wdl-publishing.properties") { expand(tokens) }
}

// No-remap loom produces the plain jar (26.x is unobfuscated); the guard is shared with the NeoForge sibling.
registerVerifyProductionJar("jar")

// Prints this subproject's resolved version (mod_version + the MC build-metadata, set by wdl.java-conventions)
// so the release workflow can assert the pushed tag equals the version it is about to publish. Registered on a
// loader subproject because the root project carries no version; captured into a val for configuration-cache
// compatibility (no Project access inside the task action).
tasks.register("printVersion") {
    val projectVersion = version.toString()
    doLast { println("PROJECT_VERSION=$projectVersion") }
}

// Release publishing (mod-publish-plugin), driven by the release workflow on a version tag. It uploads the
// remapped Fabric jar to Modrinth and creates the single GitHub release that also carries the NeoForge jar
// (attached by :neoforge's parent-linked github block). The published version is project.version (mod_version +
// the MC build-metadata, set in wdl.java-conventions), and the changelog arrives via the CHANGELOG env the release
// workflow sets; nothing publishes on an ordinary build. Coordinates live in gradle.properties.
publishMods {
    file.set(tasks.jar.flatMap { it.archiveFile })
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
        // WDL is a Java mod: Fabric API is the only hard runtime dependency. ModMenu is an optional soft dep
        // resolved at compile only, so it is not declared as a Modrinth relation.
        requires { id.set("P7dR8mSH") } // fabric-api
    }

    // CurseForge publish; projectId is curseforge_id in gradle.properties. requires() takes the CurseForge
    // slug (fabric-api), the same hard dependency the Modrinth block declares by id.
    curseforge {
        projectId.set(providers.gradleProperty("curseforge_id"))
        accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
        minecraftVersions.add(providers.gradleProperty("minecraft_version"))
        // Client-only mod: CurseForge requires at least one declared environment.
        client.set(true)
        server.set(false)
        requires("fabric-api")
    }

    github {
        repository.set("thearchive-world/archive-world-downloader")
        accessToken.set(providers.environmentVariable("GITHUB_TOKEN"))
        // target_commitish must be a branch or commit SHA, not the tag name, or GitHub 422s. GITHUB_SHA is the
        // tag's commit; GITHUB_REF_NAME would pass the tag.
        commitish.set(providers.environmentVariable("GITHUB_SHA"))
        displayName.set("${property("mod_version")} (MC ${property("minecraft_version")})")
        // This block creates the single GitHub release and carries the Fabric jar (the top-level file above).
        // The NeoForge jar rides on this same release: :neoforge's own github block attaches it via the plugin's
        // parent mechanism (parent(publishGithub)), which uploads to this task's release instead of creating a
        // second one. So the release still holds both loader jars, with no additionalFile(Project): that
        // overload resolves the project through Gradle's deprecated dependency-notation path (a hard error in
        // Gradle 10), whereas parent navigates to a task and sidesteps it.
    }
}
