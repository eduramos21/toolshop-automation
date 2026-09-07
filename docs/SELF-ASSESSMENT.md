# Self-assessment

> Written by hand, one section per phase, as each phase lands. Not generated.

## What this framework is arguing

That the weaknesses in a large, mature, genuinely capable test suite are mostly
not capability problems. They are defaults — a locator strategy, a cleanup
convention, a retry setting, a config precedence — chosen once, early, cheaply,
and then inherited by every test written afterwards. By the time the cost is
visible the defaults are load-bearing and changing one means touching hundreds
of files.

So this repo sets the opposite defaults from the first commit and reports what
each one actually cost to hold. Where a claim can be measured rather than
asserted, it is measured, and the result is recorded below even when it
contradicts the plan.

## Per-phase notes

### P0 — scaffold
_(to write)_

### P1 — version catalog, convention plugin, module skeleton
_(to write)_

### P2 — config layer and fail-fast

The layering itself was the easy part. Five ordered lookups is not hard code to
write; what took the thinking was deciding where each rule had to live so that
breaking it later would be difficult rather than merely discouraged.

Three things came out differently from how they went in.

**The uppercase-key rule is load-bearing, not style.** It started as a naming
convention. It is actually the reason the whole design has no name-translation
layer: lookups go from a dotted key to an environment variable name and never
the reverse, so `toolshop.api.baseUrl` — which maps to `TOOLSHOP_API_BASEURL`
and cannot be mapped back — is rejected rather than guessed at. Every
translation step between key shapes is a place where "the override silently
didn't apply" can hide, and there are now none.

**Rejecting an unrecognised key mattered more than rejecting a missing one.**
A missing value is loud by construction. A `-D` that overrides nothing is
silent, and has exactly the same effect as not typing it. `-Dtoolshop.api.timeuot=PT5S`
is now an error. This was not in the plan; it fell out of having enumerated the
keys for the environment-variable mapping, which is the sort of thing that only
shows up once the code exists.

**The `ci` profile duplicates `local` and was kept anyway.** The tempting move
is to alias it. But CI is precisely where the values are most likely to need to
diverge, and the point of the layering is that when they do it is a config
change and not a branch in code. A file that exists is a place to put that
change; an alias is a thing to undo first.

The cost, stated plainly: every Gradle invocation that runs tests now needs a
complete configuration, including secrets, and that includes the unit tests of
the loader itself, which have no use for a password. `./gradlew build` on a
fresh clone fails until `.env.local` exists. Each way out was worse than the
cost — see [`adr/0004`](adr/0004-configuration-is-validated-before-test-discovery.md).

### P3 — tag vocabulary and tag-driven execution

The annotations were the cheap half. Twelve files, each four lines of
boilerplate around one `@Tag`, and they buy the thing that matters: a typo is a
compile error rather than a test that quietly belongs to no suite.

What took the work was the guard, and the interesting part is *where* it had to
go. The instinct is to put it in the conventions plugin with everything else,
failing each module that selected nothing. That is wrong, and the reason is
structural: there is one layer tag per module, so `-Ptags=api` is *supposed* to
select zero tests in `ui-tests`. Only zero across the whole invocation is a
mistake. A per-module guard would have produced a false failure on the most
ordinary command in the vocabulary, and would have been switched off within a
week.

So the guard is one task in the root build, finalising every `Test` task. That
placement is not a compromise; it is the only place that can see the thing being
checked.

**Two things I checked rather than assumed.** Whether Gradle leaves stale result
XML behind when a task re-executes with a changed filter — it does not, it wipes
the output directory, which is what makes counting XML safe. And whether a
cross-project `finalizedBy` survives the configuration cache — it does.

**One thing I got to have for free.** The guard's failure message lists the tag
vocabulary, and it gets the list by reading the annotation file names in `core`
rather than holding a copy. That only stays true while a file name and the tag it
declares agree, so `TagVocabularyTest` asserts it. The same test covers the two
ways a composed annotation can be silently inert — a `@Tag` value that does not
match the annotation's name, and a missing `RUNTIME` retention. Both compile,
both apply, and both produce exactly the excluded-and-invisible test the
vocabulary exists to prevent, one level down.

**What I left open, deliberately.** `-Ptags=quarantine` finding nothing is good
news, and the guard fails it, because it cannot tell an empty quarantine list
from a typo. The obvious fix is a flag that suppresses the guard — which is a
flag that ends up in a CI file silencing a real problem. It waits for P8, where
the scheduled job that needs it actually exists.

Also honest: the parallelism configuration for `ui-tests` and `api-tests` landed
here with the threading decisions it documents, but neither module has a test
yet, so neither file has been exercised. The model in
[`THREADING.md`](THREADING.md) is reasoned from `@UsePlaywright`'s per-thread
caching, not yet from a measurement. That measurement is P5's.

## Measurements

| Claim | How it was measured | Result |
|---|---|---|
| Gradle's `failOnNoDiscoveredTests` guards empty tag selections | one-test module, zero-match tag filter, Gradle 9.7.1 | **False.** Tag filtering is post-discovery. See `docs/adr/0003`. |
| The `junit-bom` is required to stop Playwright's compile-scope `junit-jupiter-engine:5.14.1` pin winning | resolved `playwright:1.62.0 + junit-jupiter:6.1.3` in two configurations, with and without the BOM | **False on Gradle.** Identical resolution either way — Gradle is highest-wins and `junit-jupiter:6.1.3` already brings engine 6.1.3. True on Maven, which is nearest-wins. The BOM stays for versionless catalog entries and platform/jupiter consistency, which is a smaller claim than the one first written down. |
| An exception thrown from `LauncherSessionListener.launcherSessionOpened` is swallowed and logged, the way `TestExecutionListener` callbacks are | `ServiceLoader`-registered listener throwing a canary, Gradle 9.7.1, JUnit 6.1.3, single-line and multi-line messages | **False — it propagates.** `BUILD FAILED`, exit 1, zero tests executed, no results XML written, message rendered in full including line breaks. `DefaultLauncherSession` calls session listeners from its constructor, unguarded. The planned fallback — the same validation inside `ToolshopConfig.get()`, failing on the first test instead — was not needed. See `docs/adr/0004`. |

## What I would do differently
_(to write)_
