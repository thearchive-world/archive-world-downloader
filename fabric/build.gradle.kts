import net.ltgt.gradle.errorprone.errorprone
import wdl.buildlogic.registerVerifyProductionJar

plugins {
    id("fabric-loom")     // version comes from the root apply-false declaration
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

    // The project's first mixin (PauseScreenMixin) needs Loom's mixin annotation processor: it emits the refmap that
    // maps the mixin's @Shadow and @Inject targets from the Mojmap compile names to the intermediary names the
    // shipped jar runs against. Without this block Loom does not process the mixin, no refmap ships, and the mixin
    // fails to apply on a real client with "No refMap loaded". This is the band's only mixin.
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("wdl.refmap.json")
    }
}

// This band's Minecraft version predates the Fabric client game test framework (fabric-client-gametest-api-v1,
// first shipped at 1.21.4), so the capture-verification tier is gated off here via client_gametest=false in
// gradle.properties; the property is absent, hence true, from 1.21.4 up. On this band the capture proof is the
// maintainer's manual in-world check rather than this automated tier.
val clientGameTest = providers.gradleProperty("client_gametest").map(String::toBoolean).getOrElse(true)

// Automated capture-verification tier: the official Fabric Client Game Test API boots a real client
// plus an in-process server and hands the test a real ClientLevel, so the live capture front (the chunk
// snapshot reading a ClientLevel and the inbound entity Netty tee) runs headlessly. createSourceSet puts
// the harness in its own gametest source set, off the fast :common:test / build gate. Server game tests
// are not used here (the tier is client-side), so only the client tests are enabled. eula accepts the
// Minecraft EULA so the harness may write eula.txt: the tier connects the client to an in-process
// DEDICATED server over a real connection, and createServer() refuses to start until the EULA is
// accepted. The maintainer has read and accepted https://aka.ms/MinecraftEULA.
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
if (clientGameTest) {
    tasks.named<JavaCompile>("compileGametestJava") {
        options.errorprone.enabled.set(false)
    }
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
    maven("https://jm.gserv.me/repository/maven-snapshots/") { content { includeGroup("info.journeymap") } }
}

// Parchment param-name mappings are layered only on bands that publish them; a band with no Parchment
// release (26.x) omits both properties and skips the parchment layer below.
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

    // ModMenu API for the mod-list config-screen entrypoint (WdlModMenu), compile-only. Loom remaps it to
    // Mojmap for the compile; there is no runtime require, so ModMenu's absence just means the entrypoint is
    // never queried. The pinned version supplies only the stable ModMenuApi; the player's own ModMenu runs.
    modCompileOnly("maven.modrinth:modmenu:${property("modmenu_version")}")

    // JourneyMap public API for the source-merged overlay binding (compat/journeymap), compile-only. JourneyMap
    // ships no Fabric build at 1.15.2, so there is no runtime JourneyMap to query on Fabric and no -fabric-SNAPSHOT
    // flavor; the plain Mojmap 1.8 API here only satisfies the merged binding's compile and stays inert on Fabric
    // (the Fabric jar carries no journeymap entrypoint). It is a plain compileOnly, not modCompileOnly, because the
    // loader-suffixless jar is already Mojmap-named and needs no Loom remap.
    compileOnly("info.journeymap:journeymap-api:${property("journeymap_api_coordinate")}-SNAPSHOT")

    // JSpecify on the gametest source set so its package-info @NullMarked resolves; compileOnly is not
    // transitive across source sets. NullAway does not run here (test-scope, disabled above), so the marking
    // is documentary only, but it keeps the tree visibly consistent with main and test. Guarded on
    // clientGameTest: the gametestCompileOnly configuration exists only when the source set is created above.
    if (clientGameTest) {
        "gametestCompileOnly"(libs.jspecify)
    }
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

// Loom's remapJar is the producing task; the guard is shared with the NeoForge sibling (see build-logic).
registerVerifyProductionJar("remapJar")

// Prints this subproject's resolved version (mod_version + the MC build-metadata, set by wdl.java-conventions)
// so the release workflow can assert the pushed tag equals the version it is about to publish. Registered on a
// loader subproject because the root project carries no version; captured into a val for configuration-cache
// compatibility (no Project access inside the task action).
tasks.register("printVersion") {
    val projectVersion = version.toString()
    doLast { println("PROJECT_VERSION=$projectVersion") }
}

// Release publishing (mod-publish-plugin), driven by the release workflow on a version tag: it uploads the
// Fabric jar to CurseForge and Modrinth per this band's MC version. There is no github block, because the
// plugin cannot carry one GitHub release across the separate per-band runs, so the release workflow creates
// that release with gh. The changelog arrives via the CHANGELOG env; nothing publishes on an ordinary build.
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
}
