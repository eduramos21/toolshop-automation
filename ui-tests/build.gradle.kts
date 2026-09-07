plugins {
    id("toolshop.java-conventions")
}

dependencies {
    testImplementation(project(":core"))

    // The API client. UI tests create their test data through the API - an
    // account to buy with, a product to buy - so the application's own
    // validation applies and the UI test is not also a test of how rows are
    // written. It also means a UI test never depends on a seeded account whose
    // state another test could have changed.
    testImplementation(project(":api-tests"))

    // Database and mail verification, so the checkout slice can be followed all
    // the way to the invoice row and the confirmation email.
    testImplementation(project(":data"))

    testImplementation(libs.playwright)
    testImplementation(platform(libs.allure.bom))
    testImplementation(libs.allure.jupiter)
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
