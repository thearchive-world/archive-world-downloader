// The Forge jar is a separate Gradle build (an island): ForgeGradle 6 is Gradle-8-only, and one build carries one
// wrapper, so it cannot share the band's Gradle-9 root that builds common + fabric.
rootProject.name = "wdl-forge"
