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
    implementation("net.fabricmc:mapping-io:0.9.1")
    implementation("net.fabricmc:tiny-remapper:0.14.1")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
}

application {
    mainClass = "world.thearchive.wdl.bridge.MojmapBridgeGen"
}

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
