import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

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
    exclude("**/.gitkeep")
}

// GPL-3.0 section 4, which LGPL-3.0 incorporates, asks that every recipient be given a copy of the License along with
// the Program, and a mod jar travels on its own far from any listing page: modpacks, mirrors, a copied mods/ folder.
val licenseTexts = listOf(rootProject.file("LICENSE"), rootProject.file("GPL-3.0.txt"))
tasks.named<Jar>("jar") {
    from(licenseTexts) { into("META-INF") }
}
