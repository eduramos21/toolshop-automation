plugins {
    id("toolshop.java-conventions")
}

dependencies {
    // Tag annotations are part of core's PUBLIC api - they appear in the
    // signatures of the annotations other modules apply - so this is `api`,
    // and core is a java-library, not a plain java module.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}
