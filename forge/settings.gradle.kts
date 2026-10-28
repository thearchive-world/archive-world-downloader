// The Forge jar is a separate Gradle build (an island). It runs Architectury Loom, a Fabric Loom fork, while the band's
// Gradle-9 root runs Fabric Loom for fabric; two Loom plugins cannot share one build.
pluginManagement {
    repositories {
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "wdl-forge"
