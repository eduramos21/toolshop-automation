plugins {
    id("toolshop.java-conventions")
}

dependencies {
    // Tag annotations are part of core's PUBLIC api - they appear in the
    // signatures of the annotations other modules apply - so this is `api`,
    // and core is a java-library, not a plain java module.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    // ConfigValidationListener implements a JUnit Platform launcher interface, so
    // the launcher is a compile dependency of core's MAIN source set - not just
    // the testRuntimeOnly the conventions plugin adds to every module's tests.
    //
    // `implementation`, not `api`: nothing compiles against the launcher through
    // core. It only has to be on a consumer's test runtime classpath, which this
    // gives it, so ServiceLoader can find the listener.
    implementation(libs.junit.platform.launcher)
}
