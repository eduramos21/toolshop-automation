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

### P4 — API layer and the API half of the checkout slice

41 API tests, green against local and hosted from the same bytecode. The tests
themselves were the easy part. Three things cost real time, and all three were
the same kind of problem: an assumption about the application that reading the
specification would not have corrected.

**The OpenAPI specification disagrees with the application.** `POST /invoices` is
documented as answering 200 and returning `invoicelines`; it answers 201 and
returns neither `invoicelines` nor `status`. `POST /users/register` is documented
with 422 for a duplicate email and actually answers 409. Every contract in this
layer was therefore taken from probing the running application rather than from
the spec, and the spec being wrong is now itself something worth asserting -
which is precisely what the contract-validation step is for.

**Two of my own tests were the bug.** The application locks a non-administrative
account after three failed logins, checks the lock *before* the password, and
does not clear it on a correct password. My wrong-password tests were failing
logins against the shared seeded customer. Under method-level parallelism that is
a race - sometimes three failures land before a success resets the counter,
sometimes not - and once it latches, every subsequent run of the whole suite
fails with `423 Locked`.

That is the exact defect this repository argues about, committed by me, in the
first test class I wrote. Worth recording rather than quietly fixing. The fix is
not a retry and not a reset between tests: anything that must fail a login now
creates its own disposable account and deletes it in `@AfterEach`. The seeded
accounts only ever receive correct passwords, so they cannot lock. It also
enabled a test that was not possible before - that a locked account stays locked
even given the right password - because that can only be asserted safely against
an account nobody else uses.

Two details of the fail-fast design paid for themselves here. The client's login
failure names the account, the target, the status and the body, so the diagnosis
was immediate rather than a hunt. And `@AfterEach` rather than cleanup at the end
of a test body means the account is removed when the test fails, which is the
only case where it matters.

**`./run up` was reseeding without flushing the cache.** The read endpoints are
served from a server-side cache. `artisan migrate:fresh --seed` replaces every
product with a new ULID, but `/products` keeps answering with the previous set,
while cart validation goes to the database and rejects those ids as invalid. The
result is every checkout test failing with "the selected product id is invalid"
about a product `/products` is actively advertising.

The application's own `POST /refresh` does the seed, removes generated invoices
*and* flushes the cache - and the comment in its source names this exact trap.
`./run up` now calls that instead. The lesson is not about caching: it is that
the reset path was assembled out of two of the three things the application
already did in one place.

**What the buggy target turned out to be.** The hosted defect-injected build is
not sprint 5 with faults introduced. It is an older API surface - integer product
ids, `stock` rather than `in_stock`, no `POST /carts` route - reporting
`"version":"5.0"` regardless. 31 of 41 tests fail against it. That is the suite
noticing, but it is not the fair test of a defect-injected build that the plan
assumed, and the profile is documented as such rather than quietly dropped.

**Repeatability, checked rather than assumed.** Three consecutive full runs with
no reset between them: 113 tests, green each time. That was the point of the
disposable-account fix, so it is the thing worth measuring.

**One inconsistency left open.** AssertJ is declared the sole assertion
vocabulary and the API tests use it exclusively, but `core`'s 73 existing tests
still use JUnit's own assertions, because converting them is churn that belongs
in its own change rather than buried in this one.

## Measurements

| Claim | How it was measured | Result |
|---|---|---|
| Gradle's `failOnNoDiscoveredTests` guards empty tag selections | one-test module, zero-match tag filter, Gradle 9.7.1 | **False.** Tag filtering is post-discovery. See `docs/adr/0003`. |
| The `junit-bom` is required to stop Playwright's compile-scope `junit-jupiter-engine:5.14.1` pin winning | resolved `playwright:1.62.0 + junit-jupiter:6.1.3` in two configurations, with and without the BOM | **False on Gradle.** Identical resolution either way — Gradle is highest-wins and `junit-jupiter:6.1.3` already brings engine 6.1.3. True on Maven, which is nearest-wins. The BOM stays for versionless catalog entries and platform/jupiter consistency, which is a smaller claim than the one first written down. |
| REST Assured brings an object mapper, so typed payloads just work | resolved `api-tests` testRuntimeClasspath with `rest-assured:6.0.1` | **False.** Jackson is declared *optional*, so no `jackson-databind` resolves at all and record payloads have no mapper. Pinned explicitly — see `docs/ARCHITECTURE.md`. |
| Maven Central's `<release>` field names the latest release | read `maven-metadata.xml` for `assertj-core` | **Not reliably.** It reported `4.0.0-M1`. A milestone is not a release; the catalog pins `3.27.7`. |
| The hosted `with-bugs` deployment is sprint 5 with defects injected | compared its `/products` and `/carts` against the healthy hosted build | **False.** An older API surface — integer ids, `stock` not `in_stock`, no `POST /carts` — reporting `"version":"5.0"` anyway. 31 of 41 API tests fail against it. |
| An exception thrown from `LauncherSessionListener.launcherSessionOpened` is swallowed and logged, the way `TestExecutionListener` callbacks are | `ServiceLoader`-registered listener throwing a canary, Gradle 9.7.1, JUnit 6.1.3, single-line and multi-line messages | **False — it propagates.** `BUILD FAILED`, exit 1, zero tests executed, no results XML written, message rendered in full including line breaks. `DefaultLauncherSession` calls session listeners from its constructor, unguarded. The planned fallback — the same validation inside `ToolshopConfig.get()`, failing on the first test instead — was not needed. See `docs/adr/0004`. |

## What I would do differently
_(to write)_
