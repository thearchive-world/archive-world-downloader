import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

// Consumer side of the source-merge of :common into a loader subproject (MultiLoader-Template
// "commonJava"/"commonResources" pattern). These resolvable configurations pull common's SOURCE (not a
// compiled jar) and feed it to compileJava, so common's classes compile into the loader jar alongside the
// loader's own: Loom remaps them in :fabric, while :neoforge runs Mojmap-named and needs no remap. The
// producer side lives in :common.
plugins {
    java
}

val commonJava = configurations.create("commonJava") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val commonResources = configurations.create("commonResources") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("commonJava", project(path = ":common", configuration = "commonJava"))
    add("commonResources", project(path = ":common", configuration = "commonResources"))
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(commonJava)
    source(commonJava)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(commonResources)
    from(commonResources)
    exclude("**/.gitkeep")   // scaffolding placeholder; never package it
}

// Ship the license inside the artifact. GPL-3.0 section 4, which LGPL-3.0 section 0 incorporates, asks that
// every recipient be given a copy of the License along with the Program, and a mod jar travels on its own
// far from any listing page: modpacks, mirrors, a copied mods/ folder. This plugin is applied by exactly the
// two loader subprojects that produce shipped jars, and Fabric's remapJar derives from jar, so configuring
// jar here reaches both artifacts. Captured into a val so no Project reference survives into task execution
// (configuration cache).
val licenseTexts = listOf(rootProject.file("LICENSE"), rootProject.file("GPL-3.0.txt"))
tasks.named<Jar>("jar") {
    from(licenseTexts) { into("META-INF") }
}
