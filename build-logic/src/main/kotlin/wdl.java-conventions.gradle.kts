import org.gradle.api.plugins.quality.Checkstyle
import wdl.buildlogic.registerCheckLicenseHeader

// Shared JVM baseline for every wdl subproject: the Java toolchain, the lint-only Checkstyle pass,
// the common repositories, group/version, and the JUnit setup. (Spotless is applied per-subproject,
// not here: loaded from an included build its Eclipse JDT formatter fails intermittently under the
// configuration cache + build cache + parallel execution.)
plugins {
    java
    checkstyle
}

group = providers.gradleProperty("mod_group").get()
// Carry the MC patch as SemVer build metadata, e.g. 1.0.0-SNAPSHOT+1.21.11
version = "${providers.gradleProperty("mod_version").get()}+${providers.gradleProperty("minecraft_version").get()}"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases")
}

// The JDK that COMPILES, normally the language target itself. A band overrides it through
// java_toolchain_version only where the target's own JDK cannot be used: javac 16 assigns local variable slots
// non-deterministically, so a band targeting 16 compiles on 17 and keeps its jar byte-reproducible. Where the
// two differ, --release pins the bytecode to the language target so a newer compiler does not move the
// artifact; where they agree no release flag is set and compilation is byte-identical to before this existed.
val javaTarget = providers.gradleProperty("java_version").get().toInt()
val javaToolchain = providers.gradleProperty("java_toolchain_version").orNull?.toInt() ?: javaTarget

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaToolchain)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

if (javaToolchain != javaTarget) {
    tasks.withType<JavaCompile>().configureEach { options.release.set(javaTarget) }
}

// Lint-only Checkstyle, wired into check via the built-in plugin's checkstyleMain/checkstyleTest.
// Shared config at the repo root; it runs alongside the Java toolchain set just above.
checkstyle {
    toolVersion = "10.20.2"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

// Checkstyle 10.x requires Java 11+ to run, which the deep Java-8 bands cannot host on their own toolchain.
// Checkstyle analyzes source text, so its runner JVM is independent of the Java-8 language level the sources
// compile at; point the Checkstyle tasks at a modern launcher there while the compile stays on the band toolchain.
// PITest 1.22.1 is Java-11 bytecode for the same reason: it analyzes and forks against compiled class files,
// not the language level, so it can run against Java-8-compiled sources from a newer launcher. The pitest task
// is selected by name off JavaExec (its actual supertype), not by the PitestTask type, since the pitest plugin
// is applied after this convention plugin and is never on build-logic's classpath.
if (providers.gradleProperty("java_version").get().toInt() < 11) {
    val modernLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.ADOPTIUM
    }
    tasks.withType<Checkstyle>().configureEach {
        javaLauncher = modernLauncher
    }
    tasks.withType<JavaExec>().matching { it.name == "pitest" }.configureEach {
        javaLauncher = modernLauncher
    }
}

// JUnit 5 for the subprojects that have tests (common, neoforge); applied uniformly here, so it is
// inert on the test-less fabric subproject, whose test task stays NO-SOURCE. The BOM coordinate lives in
// the version catalog (Dependabot currency); read via VersionCatalogsExtension since the libs accessor
// is absent in precompiled script plugins (gradle/gradle#15383).
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Gradle's default logs FAILED only, and it prints the "N skipped" summary line just for a task that fails, so
// an aborted assumption on a green run leaves no trace at all in the console or in CI.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("skipped", "failed")
    }
}

// Reproducible archives: constant entry timestamps + stable order, so a jar rebuilt from a tag on the
// same compile-toolchain JDK is byte-identical. This is a backstop: Loom's remapJar and ModDevGradle's
// jar are already reproducible, so it locks that against a future default change and covers the sources jar.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Prints the resolved COMPILE-toolchain JDK (the JVM that emits bytecode), read from the toolchain rather
// than from a shell java on PATH, so the release reproducibility record names the JDK a third party must match.
tasks.register("printCompileJavaVersion") {
    val meta = javaToolchains.compilerFor(java.toolchain).map { it.metadata }
    doLast {
        val m = meta.get()
        println("${m.vendor} ${m.jvmVersion} (Java ${m.languageVersion.asInt()})")
    }
}

// Gate the mandatory two-line license header on every Java file (all source sets).
registerCheckLicenseHeader()
