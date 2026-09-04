# 3. An empty test selection must fail the build

Status: accepted — with a measured correction to the original plan

## Context

In a large Java test suite I assessed before starting this one, the majority of
UI tests were reachable only through catch-all suites and so ran in no smoke and
no regression job. Separately, suite selection there was driven by a CI job-name
string, and an unmatched name produced a Gradle command with empty property
values rather than an error.

Both are the same failure: a run that executes nothing and reports green. It is
the failure mode worth designing against hardest, because unlike a red build it
produces no signal that anything is wrong.

The plan assumed Gradle 9's `Test.failOnNoDiscoveredTests` (default `true`)
covered this. The Gradle 8 upgrade guide's wording — "test sources are present,
but no test was selected for execution" — reads like it does.

## Measurement

Run on Gradle 9.7.1, JUnit 6.1.3, on a module with one passing test:

| Configuration | Result |
|---|---|
| `useJUnitPlatform { includeTags("doesnotexist") }`, defaults | **BUILD SUCCESSFUL** |
| ...plus `failOnNoDiscoveredTests = true` explicitly | **BUILD SUCCESSFUL** |
| ...plus `filter { isFailOnNoMatchingTests = true }` as well | **BUILD SUCCESSFUL** |

**Neither property fires.** JUnit tag filtering is a *post-discovery* filter:
the test class is discovered, then removed. "No tests discovered" is therefore
false — one was discovered and zero were executed. Gradle's guard only covers
the discovery stage.

`filter.isFailOnNoMatchingTests` is a separate mechanism entirely; it governs
Gradle's own `--tests` patterns, not JUnit Platform filters.

## Decision

Do not rely on either property for tag-based selection. Keep both set (they
cost nothing and do catch `--tests` typos), and add an explicit check that
counts *executed* tests and fails on zero.

Implemented in P3. Both properties stay in the convention plugin with a comment
pointing here, so nobody re-derives the wrong conclusion from their names.

## Consequences

The verification for P3 is `./gradlew test -Ptags=doesnotexist` **fails** — and
that now tests our own guard rather than an assumed Gradle behaviour.

This is the single most important guarantee in the execution design: without
it, the whole tagging scheme is decorative, because a mistyped tag produces the
exact silent-green outcome the tagging exists to prevent.
