plugins {
    alias(libs.plugins.loom) apply false
    // Spotless is declared here (not in build-logic) and applied per-subproject: loaded from the
    // build-logic included build, its Eclipse JDT formatter fails intermittently
    // (InvocationTargetException) under the configuration cache + build cache + parallel execution,
    // while from the root buildscript classpath it is stable.
    alias(libs.plugins.spotless) apply false
}
