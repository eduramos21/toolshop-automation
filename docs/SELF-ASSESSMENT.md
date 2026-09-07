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

### P5 — Playwright layer and the UI half of the slice

Five lines of vendor-supported configuration replaced the three largest problems
a UI suite tends to have. `setTestIdAttribute("data-test")` makes
`getByTestId` the default locator strategy for the whole suite, so XPath is not
a choice anyone has to make. `setTrace(RETAIN_ON_FAILURE)` means the evidence
exists without a test remembering to capture it. `setOutputDir` keeps traces
inside `build/`, where `clean` finds them.

The interesting work was in what the page objects *do not* contain. The address
step has no waiting code: the application debounces its postcode lookup and
disables the proceed button until the form is valid, and Playwright's click
already waits for enabled. A sleep there would encode a guess at the debounce
interval; the absence of one encodes the actual condition.

Two things the page objects had to gain, both from measurement rather than
design.

**An explicit response wait, because auto-waiting was not enough.** Filling
country, postcode and house number triggers up to three debounced lookups, and
the proceed button can be observed enabled between them. Waiting on the lookup
response - `page.waitForResponse` around the fills - is the condition that
actually matters, and it is what the brief prescribes.

**The order of the three fills is load-bearing.** The form echoes every change up
to the checkout component and receives it back as a patch. Playwright's `fill`
is select-all-then-insert, so a patch landing between those two steps drops the
selection and appends: the postcode reached the API as `1011AB1011AB` and the
lookup answered 422. Filling the text fields first and selecting the country last
- the only one of the three that triggers a lookup without a debounce - makes it
one request with all three values already settled.

That failure is worth dwelling on because of how it was found. The first version
of the wait insisted on a 200 response, so a rejected lookup became a 30-second
timeout that said nothing at all. Letting any response through and reporting the
status, URL and body turned the next occurrence into one line naming the doubled
postcode. The diagnostic was worth more than the assertion.

### P6 — Allure 3, inline traces, failure evidence

Both spikes came back favourably, which is worth stating because the plan
carried fallbacks for both.

**S3: Allure 3 renders a Playwright trace inline.** The report's own bundle
handles `application/vnd.allure.playwright-trace`, copies the zip into
`data/attachments/` and loads it into the trace viewer. Neither fallback - an
HTML attachment iframing the viewer, or a link-out - is needed. One caveat found
while checking: the viewer *code* comes from `trace.playwright.dev`, so viewing a
trace needs internet even though the trace itself never leaves the report.

**S4: Allure 3 reads allure-java 2.x results.** `allure@3` is 3.16.1, with
`generate`, `open` and `quality-gate`. It produced a complete report, behaviour
tree included, from results written by `allure-jupiter:2.35.5`. The Allure 2 CLI
fallback is not needed.

The evidence extension needed two hooks rather than one, and that is a fact about
JUnit and Playwright rather than a preference: a screenshot needs an open page,
and the trace is written when the context closes - which
`BrowserContextExtension` does from its own `TestWatcher`. So the screenshot,
console and network journals are taken in `afterTestExecution`, and the trace is
attached in `testFailed`, by which point the zip exists. Verified with a
deliberately failing test: all five attachments present.

**Video was dropped, against the plan.** Playwright records video for every test
in order to attach it to the few that fail, and the trace already carries
screenshots, DOM snapshots, console and network for exactly those. Once the trace
was measured to render inline, video was paying on every run to duplicate
something already there.

Two costs recorded rather than hidden. The extension imports
`com.microsoft.playwright.impl.junit.PageExtension` - an `impl` package - because
`getOrCreatePage` is the only way an extension can reach the current page, and
the alternative is every test handing it over, which is the convention being
deleted. And a parameterised failure's trace is the last invocation's, because
Playwright names the directory after the method rather than the invocation.

### P7 — Database, mail, and cleanup that actually runs

The registry was the easy half. Two things it uncovered were not.

**The cleanup extension shared one registry across concurrent tests, and it took
real work to see why.** A `ParameterResolver` asked for a *constructor* parameter
receives the **class-level** `ExtensionContext`; `afterEach` receives the
**method-level** one. Store lookups inherit from the parent, so a registry
created during the constructor lands in the class store and every test in the
class shares it. With concurrent methods that is destructive rather than untidy:
the first test to finish drains the registry and deletes accounts belonging to
tests still running.

It surfaced as a login answering 401 with a correct password - the account had
been deleted underneath the test mid-flow - which looks nothing like its cause. I
chased the application's login code, the lockout counter and the database before
measuring the context identity, which took one print statement and answered it
immediately. The lesson is the ordering of those two steps, not the fact itself.

The fix is a method parameter, where the context is the method's own, plus a
guard: asking for a registry anywhere else now fails with an explanation instead
of silently sharing one.

**A completed order cannot be undone through the API.** `DELETE /users/{id}`
answers 409 for a customer who has an invoice - a foreign key - and there is no
endpoint that removes an invoice. So a checkout test can only clean up after
itself where the database is reachable, which is why those tests carry `@Db`.
The delete order is `payments` then `invoice_items` then `invoices`, each
discovered by the constraint rejecting the previous attempt rather than by
reading the schema - the honest order to find it in.

That is the one write in an otherwise read-only data layer, and the distinction
matters: the read-only rule exists so that test data is *created* through the
API, where the application's validation and side effects apply. Removing what a
test created is the opposite of that - it is what makes the next run independent
of this one.

**Mail needed a poll, and then a bigger budget.** The confirmation is a queued
job that happens to run inline because the queue driver defaults to `sync`, so
there is no push to wait on. A deadline-bounded poll is not a fixed sleep - it
returns as soon as the condition holds - but the first budget of 20 seconds was
too small under parallel load and produced a confusing failure, because the
newest message to a fresh account is the *registration* email until the
confirmation arrives. Matching on subject as well as recipient, and listing the
mailbox in the failure message, made the next occurrence obvious.

**The defect the slice found.** Pressing "finish" once shows a payment success
message and creates no order. `checkPayment()` fires its request and then returns
`of(this.state)` - the field's value before the response arrives - so the first
click always evaluates `undefined` and `POST /invoices` is never sent. The
invoice call's error handler is also empty, so a real failure would look
identical.

This is the argument for the whole framework in one place. A suite that asserted
on the payment message would report this as passing. Following the purchase to
the invoice row and the customer's inbox is what makes it visible.
`CheckoutDefectUiTest` asserts the correct single-click behaviour and is
quarantined, because the application is third-party here and asserting the broken
behaviour would turn a defect into a requirement.

### P8 — CI parity and a pinned application

The change that mattered was not the workflow file. It was that
`docker/docker-compose.sut.yml` lives here and `./run up` uses it, so **no clone
of the application is needed at all**. Before this, the version under test was
whatever a developer's clone happened to be on, which makes "it passes locally"
an unfalsifiable claim.

Every image is pinned by digest rather than tag, and that distinction is not
pedantry: `sprint5-api:2.4` is mutable, and `web` and `cron` publish only
`latest`. A tag does not describe a version.

Two things the application's own compose files lack, added here because their
absence causes failures that look like product bugs: a MariaDB healthcheck with
the API waiting on it, and MailCatcher — which lives only in the application's
`docker-compose.override.yml`, so a run built from its production compose has no
mailbox and every mail assertion fails for an unrelated reason.

The workflow contains no logic. Every step is `./run <something>` or a script in
`scripts/`, including the job summary, because anything that exists only in a
workflow cannot be run or debugged locally and the first time it matters is when
it breaks.

**What I could verify and what I could not.** The pinned images run the whole
suite green — 136 tests at the time — and the workflow's steps were rehearsed
locally with `.env.local` moved away, so the credentials came only from the
environment, at `TOOLSHOP_PROFILE=ci`. What I could not verify is the workflow
running on GitHub: Actions cannot be executed here, so the action versions and
expression syntax are unchecked. The first push is what verifies the wiring, and
saying so is more useful than implying otherwise.

Two plan items were dropped after measurement rather than for convenience.
`/dev/shm` needs no adjustment because tests run on the runner rather than in a
container. And Allure is an uploaded artefact rather than a GitHub Pages site:
Pages was in the plan partly so the trace viewer would have a reachable URL, and
P6 established that Allure loads the trace from the report's own files. What was
left did not justify a step that silently does nothing until Pages is enabled.

### P9 — OpenAPI contract validation

This was the step that found the most, and almost none of it was where I
expected.

**The document is OpenAPI 3.2, and no Java validator supports 3.2.**
swagger-parser does not recognise the version, silently falls back to Swagger 2
parsing, and then rejects `content` and `requestBody` as "unexpected" on every
operation in the file — an error message that says nothing about the actual
problem. Setting the version to 3.1.0 left exactly one complaint: seven paths
declare a `query` operation, which is an HTTP method OpenAPI 3.2 added. The
application really does use it; the storefront sends `QUERY /products`. So the
document is a genuine 3.2 document, not a mislabelled one, and the normalising
is two removals of things this suite does not exercise.

**Turning the validator on failed 28 of 41 API tests.** Thirteen distinct
disagreements, and every one is the document under-reporting what the
application answers — not once the other way round, which would have been more
alarming. `POST /invoices` returns 201 where only 200 is documented. Every
`/reports/*` route and `GET /users` return an undocumented 403 while documenting
401, and those are answers to different questions: "I do not know who you are"
against "I know exactly who you are and no". A client written from this document
would have no reason to handle the second.

The design question was what to do with thirteen known-wrong things. A blanket
level change would have made the suite green and the check worthless — and it
would have hidden the thirteenth, a 423 for a locked account, which was not in
the first measurement because no account happened to be locked during it and
which failed the very next run. So the whitelist is declared entry by entry with
a reason, and `ApiContractTest` replays each interaction against a validator
with no whitelist and asserts the deviation is still there. Without that second
half a whitelist is a graveyard.

Leaving the filter global rather than confining it to the contract suite is what
made the 28 failures free. The fourteenth deviation will be too.

### P10 — Accessibility

The cheapest step by far, and it still produced a finding I would have missed.

**A scan that does not wait for the page under-reports.** The first pass said
the sign-in page had zero violations. With
`waitForLoadState(NETWORKIDLE)` it consistently reports a **critical**
`button-name` — a button a screen reader announces as "button" and nothing else,
on the page where people sign in. A lazily-rendered control had not been given
its label yet when the unwaited scan ran.

That is worse than not scanning, because it produces a green tick. It is also
the one place I have deliberately used `NETWORKIDLE`, which Playwright
discourages: for waiting on a specific element there is almost always a better
condition, but "the page has finished loading everything" is precisely the
precondition for scanning a whole page.

Asserting against a per-page baseline rather than zero was the other decision.
Zero would be red from the first day and therefore ignored; "no more than
before" lets a fix go unnoticed and lets a regression hide behind an unrelated
one. Equality catches both directions, at the cost of a baseline that has to be
re-measured when a page changes.

Six pages scanned, three violations across two of them, and the two worst are on
the authentication forms.

### Postscript: the timeout that was hiding a design problem

The mail assertion needed its budget raised from 20 seconds to 60 during P7. I
raised it, noted that the confirmation was a queued job, and moved on. That was
the mistake — not the number, the incuriosity. A timeout that has to be raised is
evidence about the system, and I treated it as a parameter.

CI is where it came due: 60 seconds was not enough on a runner, and the failure
looked like flakiness. It was not. The application ships
`queue.default=database`, nothing in either container drains the queue, and one
suite run leaves fifteen rows in the `jobs` table. Mail arrived when something
happened to process it. Every green run on my laptop had been luck of a
consistent kind.

The fix is one line of SUT configuration — `QUEUE_CONNECTION=sync` — and the
budget then drops to 15 seconds because the send is inline. What I should have
done in P7 is what the failure eventually forced: look at why, rather than at
how long.

Worth saying plainly because the repository argues against exactly this. A
retry, a longer sleep and a raised timeout are the same move, and I made the
respectable-looking version of it.

### Postscript: the residue check that was not looking at everything

P7 closed on "running the suite twice leaves no residue", verified against users
and invoices. Both were zero, repeatedly. The check was still wrong, and it took
someone else running the suite to show it: **stock is residue too**. Every
checkout decrements it, nothing restores it but `./run up`, and the application
does not guard the floor - one product reached -50.

So the suite had a cumulative side effect on its own test data that its own
teardown could never have caught, because teardown removes what a test created
and this was a test *consuming* what it did not create. Worth writing down
because the fix was easy and finding it was not: I verified the residue I had
thought to look for.

The failure it produced was also a good advertisement for the diagnostics. The
report attached the browser's own alert - "You can only have one Thor Hammer in
the cart." - which named the second bug in the same run.

## Measurements

| Claim | How it was measured | Result |
|---|---|---|
| Gradle's `failOnNoDiscoveredTests` guards empty tag selections | one-test module, zero-match tag filter, Gradle 9.7.1 | **False.** Tag filtering is post-discovery. See `docs/adr/0003`. |
| The `junit-bom` is required to stop Playwright's compile-scope `junit-jupiter-engine:5.14.1` pin winning | resolved `playwright:1.62.0 + junit-jupiter:6.1.3` in two configurations, with and without the BOM | **False on Gradle.** Identical resolution either way — Gradle is highest-wins and `junit-jupiter:6.1.3` already brings engine 6.1.3. True on Maven, which is nearest-wins. The BOM stays for versionless catalog entries and platform/jupiter consistency, which is a smaller claim than the one first written down. |
| REST Assured brings an object mapper, so typed payloads just work | resolved `api-tests` testRuntimeClasspath with `rest-assured:6.0.1` | **False.** Jackson is declared *optional*, so no `jackson-databind` resolves at all and record payloads have no mapper. Pinned explicitly — see `docs/ARCHITECTURE.md`. |
| Maven Central's `<release>` field names the latest release | read `maven-metadata.xml` for `assertj-core` | **Not reliably.** It reported `4.0.0-M1`. A milestone is not a release; the catalog pins `3.27.7`. |
| The hosted `with-bugs` deployment is sprint 5 with defects injected | compared its `/products` and `/carts` against the healthy hosted build | **False.** An older API surface — integer ids, `stock` not `in_stock`, no `POST /carts` — reporting `"version":"5.0"` anyway. 31 of 41 API tests fail against it. |
| A `ParameterResolver` asked for a constructor parameter receives the test method's `ExtensionContext` | printed `getUniqueId()` from `resolveParameter` and from `afterEach` | **False.** It receives the CLASS context, so a per-test object created there is shared by every test in the class. With concurrent methods, one test's teardown deletes another's data mid-run. |
| Allure 3 needs Allure 3 results, or a migration | generated a report from `allure-jupiter:2.35.5` output with `allure@3` (3.16.1) | **False.** It reads allure-java 2.x results directly, behaviour tree included. Spike S4; the Allure 2 CLI fallback was not needed. |
| Allure renders a Playwright trace inline | attached `application/vnd.allure.playwright-trace` from a deliberately failed test and inspected the generated report | **True.** The zip is copied to `data/attachments/`, marked used, and loaded into the trace viewer. Spike S3. Caveat: the viewer code is served from `trace.playwright.dev`, so viewing needs internet. |
| Playwright's `fill` is atomic enough for a reactive form that echoes its own changes | read the URL of the rejected lookup from the failure message | **False.** `fill` is select-all-then-insert; a patch landing between them appends, and the postcode reached the API as `1011AB1011AB`. Order the fills so the lookup trigger is last. |
| A Java OpenAPI validator can read the application's document | loaded it with swagger-request-validator 2.46.1 | **False.** The document is OpenAPI **3.2**; swagger-parser does not know the version, falls back to Swagger 2, and rejects `content` and `requestBody` on every operation. At 3.1.0 the only remaining complaint is the 3.2-only `query` operation, which the application genuinely uses. |
| Maven Central's `<release>` names a usable version | requested the jar for `swagger-request-validator-restassured:3.0.0` | **False, for the second time.** 404 — no jar published. The catalog pins `2.46.1`. AssertJ's `<release>` was a milestone. Read the version list, not the field. |
| An accessibility scan reports what is on the page | scanned the sign-in page with and without waiting for load | **Not without waiting.** Unwaited: zero violations. Waited: a consistent **critical** `button-name`. A scan that under-reports is worse than none, because it produces a green tick. |
| Contract validation can be turned on quietly | enabled it as a filter on the shared client | **No.** 28 of 41 API tests failed, from 13 distinct deviations — all of them the document under-reporting the application. That is the finding, not an obstacle to it. |
| "Any product in stock" is a safe way to pick test data | ran the suite until it broke, then read the catalogue | **False, twice over.** Taking the *first* in-stock product concentrated every purchase in a run on one product - about fifteen units against a seeded stock of twenty-five - so two runs exhausted it and the application let stock go to **-50** without complaint. And reading only page one meant that once those were gone, the one remaining in-stock product was the Thor Hammer, which `CartService` caps at one per cart by comparing the product **name**. Arbitrary test data has to be arbitrary within a stated constraint, not simply first. Now: pages the catalogue, excludes per-cart limits, picks at random. |
| The checkout email is sent inline, because the queue driver defaults to `sync` | read `config('queue.default')` in the shipped image, counted rows in `jobs`, listed processes in both containers | **False, and it mattered.** The image ships `database`. `SendCheckoutEmail implements ShouldQueue`, nothing drains the queue - php-fpm only in the API container, an empty `/etc/periodic/15min` in cron - and one suite run leaves 15 pending jobs. Delivery was opportunistic: within a minute on a laptop, not within a minute on a CI runner. The pinned compose now sets `QUEUE_CONNECTION=sync`; 6 of 6 checkouts deliver, 0 jobs pending, and the poll budget dropped from 60s to 15s. |
| Searching a product's own full name finds that product | `/products/search?q=<full name>` against the seeded catalogue | **False.** Zero rows. The search is `MATCH(name) AGAINST(? IN BOOLEAN MODE)`, which requires every word, and `with` is a MySQL stopword. The first two words of the same name return three products. The UI search test now takes its term and its expected ids from the application. |
| A UI success message is evidence that the action succeeded | followed a checkout to the invoice table and the mailbox | **False, on this application.** One press of "finish" shows a payment success message and creates no order. See `docs/adr/0005` and `CheckoutDefectUiTest`. |
| An exception thrown from `LauncherSessionListener.launcherSessionOpened` is swallowed and logged, the way `TestExecutionListener` callbacks are | `ServiceLoader`-registered listener throwing a canary, Gradle 9.7.1, JUnit 6.1.3, single-line and multi-line messages | **False — it propagates.** `BUILD FAILED`, exit 1, zero tests executed, no results XML written, message rendered in full including line breaks. `DefaultLauncherSession` calls session listeners from its constructor, unguarded. The planned fallback — the same validation inside `ToolshopConfig.get()`, failing on the first test instead — was not needed. See `docs/adr/0004`. |

## What I would do differently
_(to write)_
