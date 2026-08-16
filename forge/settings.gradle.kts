// The Forge jar is a separate Gradle build (an island): ForgeGradle 6 is Gradle-8-only, and one build carries
// one wrapper, so it cannot share the band's Gradle-9 root that builds common + fabric. Its own wrapper is
// pinned to 8.1.1 under gradle/wrapper. ForgeGradle is applied from the buildscript classpath in
// build.gradle.kts (it publishes no plugin marker), so no pluginManagement repository is needed here.
rootProject.name = "wdl-forge"
