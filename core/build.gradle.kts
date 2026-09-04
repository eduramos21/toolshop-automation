// P0 declares dependencies inline. P1 replaces this with the version catalog
// and the toolshop.java-conventions plugin.
plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    // The BOM is load-bearing, not hygiene: it is the single point that pins
    // every org.junit.* artifact to one version. See docs/adr/0003.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Gradle 9 removed automatic test-framework dependency loading.
    // Without this line the test task fails to start.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
