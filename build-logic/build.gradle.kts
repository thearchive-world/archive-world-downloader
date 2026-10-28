plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Spotless is deliberately NOT here: it is applied per-subproject because loaded from this included build, its
    // Eclipse JDT formatter fails intermittently under the configuration cache + build cache + parallel execution.
    implementation(libs.errorprone.gradle.plugin)
}
