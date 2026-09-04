plugins {
    id("toolshop.java-conventions")
}

dependencies {
    testImplementation(project(":core"))
}

dependencies {
    testImplementation(libs.playwright)
}

// Playwright's Java version and its bundled browser build are coupled, so the
// browser is installed from the same pinned artifact rather than separately.
tasks.register<JavaExec>("installBrowsers") {
    group = "playwright"
    description = "Downloads the browser build pinned to Playwright ${libs.versions.playwright.get()}"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "--with-deps", "chromium")
}
