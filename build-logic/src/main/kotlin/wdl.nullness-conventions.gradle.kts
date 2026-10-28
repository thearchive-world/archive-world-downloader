import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone")
}

// These coordinates live in the version catalog so Dependabot can track their currency; the type-safe
// libs accessor is not generated inside precompiled script plugins (gradle/gradle#15383), so read them
// back through VersionCatalogsExtension.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val jspecify = libs.findLibrary("jspecify").get()
    compileOnly(jspecify)
    testCompileOnly(jspecify)

    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
}

// NullAway runs as an Error Prone plugin, scoped to nullness only: disableAllChecks turns off every other Error Prone
// check (Checkstyle and Spotless own style and layout, and Error Prone must not contend with them), then NullAway is
// re-enabled at ERROR.
tasks.named<JavaCompile>("compileJava") {
    options.errorprone {
        disableAllChecks.set(true)
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "world.thearchive")
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.enabled.set(false)
}
