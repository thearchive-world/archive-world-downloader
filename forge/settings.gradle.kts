// The Forge jar is a separate Gradle build (an island) beside the 8.14.5 root, provisioning its own
// Minecraft + Forge toolchain natively through Unimined (see build.gradle.kts): classic MCP mappings over the
// searge intermediary, plus the real Forge 1.12.2 API through Unimined's minecraftForge loader block. The
// pluginManagement repositories below are the same set the root's settings.gradle.kts carries for the classic-MCP
// Unimined toolchain: MinecraftForge's own maven for the loader/universal jar, and WagYourTail's releases and
// snapshots hosting the Unimined plugin itself. The island stays a separate Gradle build (its own wrapper) as a
// structural choice, not because its Gradle version or Java toolchain differ from the root's (both run 8.14.5
// and target Java 8); two wrappers, one set of coordinates read from the root gradle.properties.
pluginManagement {
    repositories {
        gradlePluginPortal {
            content { excludeGroup("org.apache.logging.log4j") }
        }
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://maven.wagyourtail.xyz/snapshots")
    }
}

rootProject.name = "wdl-forge"
