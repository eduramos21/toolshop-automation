# 2. Gradle never owns configuration

Status: accepted

## Context

A large Java test suite I assessed before starting this one read a `.env` file
during Gradle's configuration phase and called `System.setProperty` for each
entry, in the daemon JVM. Those properties propagated into forked test JVMs and
outranked `-D` flags typed on the command line. Because the Gradle daemon is
long-lived, a stale value also survived across builds.

Being careful is not a fix. The fix is removing the capability.

## Decision

The build never calls `System.setProperty`, and never reads a config file.
Gradle's only interaction with configuration is forwarding system properties
that already exist on its own invocation, unchanged, into the test JVM:

```kotlin
val forwarded = providers.systemPropertiesPrefixedBy("toolshop.")
tasks.withType<Test>().configureEach { systemProperties(forwarded.get()) }
```

All precedence lives in one Java class in `core`, where it is unit-testable and
where there is no second implementation to disagree with it.

The configuration cache is enabled in `gradle.properties` with
`problems=fail` — not for the speed. With it on, an untracked `System.getenv()`
or `System.getProperty()` in build logic is a hard build error, so a future
contributor cannot reintroduce the pattern even by accident.

## Consequences

An IntelliJ gutter run does not go through Gradle at all: nothing the `Test`
task sets exists in that JVM. Since Gradle now only ever *overrides* and never
*supplies*, the default configuration needs nothing injected — which is
precisely what makes one-click runs work with zero setup.

Known trap this also avoids: the Gradle daemon captures its environment at
start, so a forked test JVM inherits the daemon's environment, not the current
shell's. Reading config in Java at test runtime sidesteps it entirely.
