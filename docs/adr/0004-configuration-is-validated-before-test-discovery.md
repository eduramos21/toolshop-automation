# 4. Configuration is validated before test discovery

Status: accepted — with the enabling behaviour measured first

## Context

A misconfigured run has to be distinguishable from a broken product. If it is
not, the cost lands on whoever reads the report: a run against the wrong target,
or with an unresolved credential, produces failures that look exactly like
regressions and get triaged as such.

In a large Java test suite I assessed before starting this one, a placeholder
credential resolved happily, so a run with no real password authenticated as
nobody and failed somewhere downstream, several layers from the cause.

Validating inside the first test that reads a value is too late. By then there is
a red test, a stack trace, and a reader who has to work out that the product is
fine.

## The open question

JUnit's `LauncherSessionListener.launcherSessionOpened` is the earliest hook
available — earlier than any extension, and earlier than discovery. But
`TestExecutionListener` callbacks have their exceptions caught and logged, by
design, so that one bad listener cannot fail a run. If session listeners behaved
the same way, throwing from one would print a warning and then run the whole
suite anyway, which is worse than not checking at all.

The plan carried a fallback for that case: identical validation inside
`ToolshopConfig.get()`, with the same message, failing on the first test instead
of before discovery.

## Measurement

A listener throwing `IllegalStateException("canary")`, registered by
`ServiceLoader`, on Gradle 9.7.1 with JUnit 6.1.3:

| | |
|---|---|
| Propagates | **yes** |
| Build result | `BUILD FAILED`, exit code 1 |
| Tests executed | zero; no test-results XML written at all |
| Message rendering | in full, including line breaks, indented under `* What went wrong` |

`DefaultLauncherSession` calls session listeners from its constructor, unguarded,
so the throw aborts `LauncherFactory.openSession` and the run never reaches
discovery. The multi-line case was measured separately, because the value of
this check is entirely in its message being readable.

## Decision

`ConfigValidationListener implements LauncherSessionListener`, registered by
`ServiceLoader` from `core/src/main/resources/META-INF/services/`. It calls
`ToolshopConfig.get()` and does nothing else. The fallback is not needed.

Because it is a service file on the test runtime classpath, it applies to every
module that runs tests and to an IDE gutter run, with nothing to extend and
nothing to remember.

The check reports every problem at once, with a block naming each source
searched and its key count. Collecting them matters more than it sounds: fixing
one missing value only to be told about the next is the failure mode that
teaches people to distrust the message.

## Consequences

**Every Gradle invocation that runs tests requires a complete configuration,
including the secrets.** `./gradlew build` on a fresh clone fails until
`.env.local` exists. That includes the unit tests of the configuration loader
itself, which have no need of a password.

That is accepted rather than worked around. The alternatives were worse: a
placeholder default is the exact defect being designed against; making the check
conditional puts an `if` in the one place that must not have one; and exempting
`core` would mean the guarantee no longer holds uniformly. The failure names both
keys and the three ways to supply each, so the cost is one documented step in
`docs/CONFIGURATION.md` on first clone.

Rejected: validating lazily in `ToolshopConfig.get()` alone. It works, and it was
the fallback, but the first failure is then a red test — which is the exact
signal the design exists to avoid producing.
