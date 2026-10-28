pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.parchmentmc.org")
    }
}

rootProject.name = "wdl"

include("common")

// Loom reads the bridge jar while configuring :common, so no task edge in this build can produce it in time. The
// existence check sits inside obtain() rather than around the provider: a value source is re-run on every build to
// fingerprint the configuration cache, so the warm path costs one file check and a deleted jar is regenerated without a
// configuration-cache miss.
abstract class GenerateMojmapBridge : ValueSource<String, GenerateMojmapBridge.Params> {
    interface Params : ValueSourceParameters {
        val bridgeDir: DirectoryProperty
        val bridgeJar: RegularFileProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String {
        val jar = parameters.bridgeJar.get().asFile
        if (!jar.exists()) {
            exec.exec {
                workingDir = parameters.bridgeDir.get().asFile
                commandLine("./gradlew", "genBridge")
            }
        }
        return jar.absolutePath
    }
}

providers.of(GenerateMojmapBridge::class) {
    parameters.bridgeDir.set(File(settingsDir, "tools/mojmap-bridge"))
    parameters.bridgeJar.set(File(settingsDir, "tools/mojmap-bridge/build/bridge/intermediary-mojmap.jar"))
}.get()

includeBuild("tools/mojmap-bridge")
