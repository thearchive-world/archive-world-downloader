import wdl.buildlogic.registerCheckLicenseHeader

// Spotless is applied per-subproject, not here: loaded from an included build its Eclipse JDT formatter fails
// intermittently under the configuration cache + build cache + parallel execution.
plugins {
    java
    checkstyle
}

group = providers.gradleProperty("mod_group").get()
version = "${providers.gradleProperty("mod_version").get()}+${providers.gradleProperty("minecraft_version").get()}"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases")
}

// The JDK that COMPILES, normally the language target itself. A band overrides it through java_toolchain_version only
// where the target's own JDK cannot be used: javac 16 assigns local variable slots non-deterministically, so a band
// targeting 16 compiles on 17 and keeps its jar byte-reproducible. Where the two differ, --release pins the bytecode to
// the language target so a newer compiler does not move the artifact.
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

checkstyle {
    toolVersion = "10.20.2"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

// The BOM coordinate lives in the version catalog (Dependabot currency); read via VersionCatalogsExtension since the
// libs accessor is absent in precompiled script plugins (gradle/gradle#15383).
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

// Javadoc is the one tool in this build that resolves a {@link}, {@linkplain}, @see or @throws target, since
// Checkstyle's JavadocStyle checks structure only, so a reference to a symbol the band lacks otherwise ships green.
// PRIVATE member level because doclint checks only what javadoc documents, and at the default -protected every private
// member and package-private type goes unchecked.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        memberLevel = JavadocMemberLevel.PRIVATE
        addBooleanOption("Xdoclint:reference", true)
    }
}

if (providers.gradleProperty("javadoc_gate").orNull.toBoolean()) {
    tasks.named("check") { dependsOn(tasks.named("javadoc")) }
}

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
        println("${m.vendor} ${m.javaRuntimeVersion} (Java ${m.languageVersion.asInt()})")
    }
}

registerCheckLicenseHeader()
