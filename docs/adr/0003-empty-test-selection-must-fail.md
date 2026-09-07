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

## Implementation

`verifyTestSelection`, a task in the root build file. It finalises every `Test`
task, sums executed tests across every module's JUnit XML, and fails on zero.

Three details that are decisions rather than mechanics:

**It is per invocation, not per module.** A layer tag names a module —
`-Ptags=api` is *supposed* to select zero tests in `ui-tests` and `data`. Only
zero across the whole run is a mistake, which is why the guard sits at the root
rather than in the conventions plugin with everything else.

**It is only wired into the graph when `-Ptags` is present.** An ordinary run has
one fewer task, and a configuration failure reports one error rather than two.

**Its failure message lists the vocabulary by reading the annotation file names
in `core`**, so the build holds no second copy of the tag list to drift out of
date. `TagVocabularyTest` is what keeps a file name and the tag it declares in
step; without it that message could go quietly stale.

Reading the JUnit XML is safe against stale results because Gradle wipes a
task's output directory when it re-executes, and changing `-Ptags` changes the
task's inputs, so a re-run always rewrites. Measured.

## Known limits

**A single-module invocation can pass wrongly.** `./gradlew :core:test -Ptags=x`
leaves the other modules' `Test` tasks out of the graph entirely, so their result
directories keep XML from an earlier full run and get counted. The exact-count
guarantee holds for any invocation where the `Test` tasks actually run, which is
every form CI and `./run` use. Closing it properly needs per-task participation
through a build service, which is more machinery than the case is worth.

**`-Ptags=quarantine` selecting nothing is good news, and this fails it.** An
empty quarantine list is the desired state, but the guard cannot tell that apart
from a typo. The scheduled quarantine job in P8 needs an answer; deliberately not
a `-PallowEmptySelection` flag, because a flag that defeats the guard is a flag
that ends up in a CI file silencing a real problem.

**With `-Ptags` present, a configuration failure reports twice** — once from the
`Test` task, once from the guard finding zero. The configuration message is the
informative one and is listed first.

## Consequences

The verification is `./gradlew test -Ptags=doesnotexist` **fails**, and
`./gradlew test -Ptags='smoke & admin'` — a valid expression matching nothing —
fails too. That now tests our own guard rather than an assumed Gradle behaviour.

This is the single most important guarantee in the execution design: without
it, the whole tagging scheme is decorative, because a mistyped tag produces the
exact silent-green outcome the tagging exists to prevent.
