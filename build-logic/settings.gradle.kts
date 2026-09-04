dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // The relative path resolves against build-logic/, so this reaches the main
    // build's catalog. One catalog, two builds.
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
