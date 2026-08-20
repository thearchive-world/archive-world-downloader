plugins {
    application
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("net.fabricmc:mapping-io:0.8.0")
    implementation("net.fabricmc:tiny-remapper:0.14.0")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
}

application {
    mainClass = "world.thearchive.wdl.bridge.MojmapBridgeGen"
}

// Composes obf(1.13.2) -> Mojmap(1.14.4) and derives the intermediary->named jar and mojmap->srg
// reobf mapping. Downloads and caches its pinned immutable sources under build/sources on first run;
// the generator self-validates and fails the build on any mismatch.
tasks.register<JavaExec>("genBridge") {
    group = "build"
    description = "Generates the 1.13.2 Mojmap bridge mappings."
    mainClass = "world.thearchive.wdl.bridge.MojmapBridgeGen"
    classpath = sourceSets["main"].runtimeClasspath
    val outDir = layout.buildDirectory.dir("bridge")
    val cacheDir = layout.buildDirectory.dir("sources")
    args = listOf(
        "--out", outDir.get().asFile.absolutePath,
        "--cache", cacheDir.get().asFile.absolutePath,
    )
    outputs.dir(outDir)
}
