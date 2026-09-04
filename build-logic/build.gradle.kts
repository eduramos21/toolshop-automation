// Kotlin over Groovy for one reason: `kotlin-dsl` COMPILES precompiled script
// plugins, so a typo'd task property is a build failure rather than something
// discovered when the task eventually runs.
plugins {
    `kotlin-dsl`
}
