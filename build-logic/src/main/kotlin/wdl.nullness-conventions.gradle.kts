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

// The Error Prone javac plugin runs inside the compile toolchain, and the Error Prone release the catalog pins is a
// Java 21 build that a lower toolchain cannot load.
val errorProneUsable = providers.gradleProperty("java_version").get().toInt() >= 21

dependencies {
    val jspecify = libs.findLibrary("jspecify").get()
    compileOnly(jspecify)
    testCompileOnly(jspecify)

    if (errorProneUsable) {
        "errorprone"(libs.findLibrary("errorprone-core").get())
        "errorprone"(libs.findLibrary("nullaway").get())
    }
}

if (errorProneUsable) {
    // NullAway runs as an Error Prone plugin, scoped to nullness only: disableAllChecks turns off every other Error
    // Prone check (Checkstyle and Spotless own style and layout, and Error Prone must not contend with them), then
    // NullAway is re-enabled at ERROR.
    tasks.named<JavaCompile>("compileJava") {
        options.errorprone {
            disableAllChecks.set(true)
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:AnnotatedPackages", "world.thearchive")
        }
    }

    tasks.named<JavaCompile>("compileTestJava").configure {
        options.errorprone.enabled.set(false)
    }
} else {
    // Below the Error Prone floor the plugin is still applied (the plugins block is static) but must not
    // attach to any compile task, since no errorprone dependency was added to load it.
    tasks.withType<JavaCompile>().configureEach {
        options.errorprone.enabled.set(false)
    }
}
