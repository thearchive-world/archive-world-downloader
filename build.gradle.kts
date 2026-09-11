// Shared build logic lives in the build-logic included build as wdl.* convention plugins
// (wdl.java-conventions, wdl.nullness-conventions); each subproject applies the ones it needs. The
// catalog-pinned plugins are declared here apply-false for the subprojects to apply.
plugins {
    alias(libs.plugins.loom) apply false
    // Spotless is declared here (not in build-logic) and applied per-subproject: loaded from the
    // build-logic included build, its Eclipse JDT formatter fails intermittently
    // (InvocationTargetException) under the configuration cache + build cache + parallel execution,
    // while from the root buildscript classpath it is stable.
    alias(libs.plugins.spotless) apply false
}
