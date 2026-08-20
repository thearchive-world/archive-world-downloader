// The Forge jar is a separate Gradle build (an island), a plain-java build on its own wrapper beside the band's
// Gradle-9 root. It carries no Loom, so it needs no plugin repositories: the root's Fabric Loom provisions the
// Mojmap Minecraft classpath and publishes it to ../common/build/island-classpath.txt, which the island reads as
// plain file dependencies. The island stays separate because it runs a Java 8 toolchain distinct from the root;
// two wrappers, one set of coordinates read from the root gradle.properties.
rootProject.name = "wdl-forge"
