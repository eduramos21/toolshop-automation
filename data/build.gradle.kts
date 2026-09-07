plugins {
    id("toolshop.java-conventions")
}

dependencies {
    // Database and mail verification is framework code with consumers in other
    // modules, so it lives in src/main.
    api(project(":core"))

    implementation(libs.mariadb)

    // MailCatcher's API is JSON over HTTP. The HTTP half is java.net.http, which
    // is in the JDK; only the parsing needs a library, and Jackson is already
    // here for the API payloads.
    implementation(libs.jackson.databind)
}
