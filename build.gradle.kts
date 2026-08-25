// Shared build logic lives in the build-logic included build as wdl.* convention plugins
// (wdl.java-conventions, wdl.nullness-conventions, wdl.common-merge); each subproject applies the
// ones it needs. Only the per-loader plugins are declared here for the subprojects to apply.
plugins {
    // No loom/moddev apply-false here: this band is Forge-only and includes only :common (settings.gradle.kts),
    // so neither Fabric Loom nor NeoForge's ModDevGradle is ever applied by anything this root build configures.
    // Declaring either apply-false would still force Gradle to resolve its plugin marker at settings time, and
    // Loom 1.17.19's marker requires Gradle's plugin API 9.5.0+, incompatible with this band's Gradle 8.14.5
    // pin (indeed with any Gradle 8.x) -- exactly why this band provisions Forge through Unimined instead.
    // Spotless is declared here (not in build-logic) and applied per-subproject: loaded from the
    // build-logic included build, its Eclipse JDT formatter fails intermittently
    // (InvocationTargetException) under the configuration cache + build cache + parallel execution,
    // while from the root buildscript classpath it is stable.
    alias(libs.plugins.spotless) apply false
    // The mod-publish-plugin is declared here apply-false so each loader subproject's publishMods
    // configuration shares one catalog-pinned version; only :fabric and :neoforge apply it.
    alias(libs.plugins.mod.publish.plugin) apply false
}
