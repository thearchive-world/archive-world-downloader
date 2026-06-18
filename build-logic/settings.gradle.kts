pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Share the main build's version catalog so the convention plugins' own plugin dependencies are
// versioned from the same gradle/libs.versions.toml.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
