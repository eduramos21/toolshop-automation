// Applied by every module. One plugin, not several: a separate "playwright
// conventions" plugin would have exactly one consumer, and splitting java from
// test conventions would have zero consumers on the java-without-tests side.

plugins {
    `java-library`
}

// Precompiled script plugins do not get type-safe `libs.` accessors. This is
// the supported way to read the catalog from here.
val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
    IllegalStateException("No library alias '$alias' in gradle/libs.versions.toml")
}.get()

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    // -parameters keeps parameter names at runtime, which readable
    // @ParameterizedTest report names depend on.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

dependencies {
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))

    // Required on Gradle 9. Automatic test-framework dependency loading was
    // removed, so without this the test task cannot start.
    testRuntimeOnly(lib("junit-platform-launcher"))
}

// -Ptags carries a JUnit Platform tag expression: -Ptags="ui & checkout"
// Tag vocabulary and the empty-selection guard arrive in P3.
val tagExpression = providers.gradleProperty("tags")

// The build's ONLY interaction with configuration: forwarding system properties
// that already exist on its own invocation, unchanged, into the test JVM.
//
// It never reads a configuration file and never calls System.setProperty. That
// is the point - a build that sets properties does so after the command line has
// been parsed, so a file silently outranks a -D typed by hand, and a long-lived
// daemon carries the stale value into the next build. All precedence lives in
// one Java class instead. See docs/adr/0002-gradle-never-owns-configuration.md.
//
// Forwarding only, never supplying, is also what makes an IDE gutter run work
// with no run configuration: nothing here is required for a default run.
val forwardedConfig = providers.systemPropertiesPrefixedBy("toolshop.")

// The same mechanism covers JUnit Platform properties, so a CI-specific
// parallelism setting is -Djunit.jupiter.execution.parallel.config... on the
// invocation rather than a branch on an is-this-CI check in test code.
val forwardedJunit = providers.systemPropertiesPrefixedBy("junit.")

tasks.withType<Test>().configureEach {
    systemProperties(forwardedConfig.get())
    systemProperties(forwardedJunit.get())

    useJUnitPlatform {
        if (tagExpression.isPresent) {
            val expression = tagExpression.get()
            require(expression.isNotBlank()) {
                "-Ptags was supplied but is empty. Use e.g. -Ptags=smoke or -Ptags=\"ui & smoke\"."
            }
            includeTags(expression)
        }
    }

    // Both of these are ON but neither catches a tag expression that matches
    // nothing - measured, see docs/adr/0003. They are kept because they do
    // catch a mistyped --tests pattern. The real guard lands in P3.
    failOnNoDiscoveredTests = true
    filter { isFailOnNoMatchingTests = true }

    // All concurrency is configured in each module's junit-platform.properties.
    // Forking multiplies browser processes and discards Playwright's per-thread
    // reuse, so there is deliberately only one knob.
    maxParallelForks = 1

    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed", "skipped")
    }
}
