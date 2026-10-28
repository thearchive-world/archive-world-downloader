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
