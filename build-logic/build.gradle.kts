plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // The Error Prone plugin is applied + configured by wdl.nullness-conventions, so it must be on
    // build-logic's compile classpath. Its version comes from the shared version catalog.
    // Spotless is deliberately NOT here: it is applied per-subproject (see the root plugins block and
    // the subproject build files) because loaded from this included build, its Eclipse JDT formatter
    // fails intermittently under the configuration cache + build cache + parallel execution.
    implementation(libs.errorprone.gradle.plugin)
}
