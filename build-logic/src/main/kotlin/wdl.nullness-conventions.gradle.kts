import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

// NullAway-based nullness checking, shared by every wdl subproject's production compile.
plugins {
    java
    id("net.ltgt.errorprone")
}

// These coordinates live in the version catalog so Dependabot can track their currency; the type-safe
// libs accessor is not generated inside precompiled script plugins (gradle/gradle#15383), so read them
// back through VersionCatalogsExtension.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The Error Prone javac plugin runs inside the compile toolchain, and the pinned release (2.50) is a
// Java 21 build that a lower toolchain cannot load. A band below that floor (this one targets Java 17)
// therefore runs annotations-only, the same posture the deep Java 8 bands take. The nullness verdict is
// not lost: the shared core and adapter are byte-identical to the Java 21 bands that do run NullAway, so
// the only checked surface this band forgoes is its own plug.
val errorProneUsable = providers.gradleProperty("java_version").get().toInt() >= 21

dependencies {
    // JSpecify nullness annotations (@NullMarked, @Nullable). CLASS-retention and compile-only,
    // so nothing enters the runtime jar; supplied to both main and test compilation.
    val jspecify = libs.findLibrary("jspecify").get()
    compileOnly(jspecify)
    testCompileOnly(jspecify)

    // Error Prone hosts NullAway; every other Error Prone check is disabled on the compile tasks
    // below, so this contributes the nullness checker and nothing that contends with Checkstyle.
    if (errorProneUsable) {
        "errorprone"(libs.findLibrary("errorprone-core").get())
        "errorprone"(libs.findLibrary("nullaway").get())
    }
}

if (errorProneUsable) {
    // NullAway runs as an Error Prone plugin, scoped to nullness only: disableAllChecks turns off every
    // other Error Prone check (Checkstyle and Spotless own style and layout, and Error Prone must not
    // contend with them), then NullAway is re-enabled at ERROR. AnnotatedPackages marks our own code as
    // the checked, @NullMarked surface; everything outside it (MC, the JDK) stays unannotated and
    // is assumed non-null, so NullAway enforces our internal consistency without modeling MC.
    // In the loader subprojects this compile also re-checks :common's source merged in by
    // wdl.common-merge; AnnotatedPackages keeps the verdict identical to :common's own run.
    tasks.named<JavaCompile>("compileJava") {
        options.errorprone {
            disableAllChecks.set(true)
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:AnnotatedPackages", "world.thearchive")
        }
    }

    // Production code only. Test code stays @NullMarked for IDE and documentation value but stays
    // unchecked: JUnit lifecycle initialization (fields set in @BeforeEach or @BeforeAll) trips
    // NullAway's field-initialization analysis with no production payoff.
    tasks.named<JavaCompile>("compileTestJava") {
        options.errorprone.enabled.set(false)
    }
} else {
    // Below the Error Prone floor the plugin is still applied (the plugins block is static) but must not
    // attach to any compile task, since no errorprone dependency was added to load it.
    tasks.withType<JavaCompile>().configureEach {
        options.errorprone.enabled.set(false)
    }
}
