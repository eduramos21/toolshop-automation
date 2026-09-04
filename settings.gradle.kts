pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "toolshop-automation"

include("core")       // config, tags, lifecycle extensions
include("api-tests")  // REST Assured
include("ui-tests")   // Playwright
include("data")       // database and mail verification
