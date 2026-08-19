// The Forge jar is a separate Gradle build (an island). It runs Architectury Loom, a Fabric Loom fork, while the
// band's Gradle-9 root runs Fabric Loom for common + fabric; two Loom plugins cannot share one build, and
// loom.platform=forge is a build-wide switch, so the island stays separate with its own wrapper and one set of
// coordinates read from the root gradle.properties. The Architectury Loom plugin resolves from its own Maven and
// the loader Mavens declared here.
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
