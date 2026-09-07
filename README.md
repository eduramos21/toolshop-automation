# toolshop-automation

A test automation framework — UI, API, database and contract layers — built
against the [Toolshop](https://github.com/testsmith-io/practice-software-testing)
demo application, running identically on a laptop and in GitHub Actions.

Java 21 · Gradle 9.7.1 · JUnit 6 · Playwright · REST Assured · Allure

## Quick start

```sh
cp .env.local.example .env.local        # then fill in the passwords
./gradlew :ui-tests:installBrowsers     # once, pinned to the Playwright version
./run up                                # start the application, wait for it, reset its data
./run test                              # run the suite
./run report                            # render the Allure report and open it
```

No clone of the application is needed. `docker/docker-compose.sut.yml` pins every
image by digest, so a laptop and a CI runner bring up the same thing — set
`TOOLSHOP_SUT_DIR` only if you want to run against a clone you are editing.

One wrinkle worth knowing: the application's images are split across
architectures — the API and UI are `linux/amd64`, the web proxy and cron are
`linux/arm64` — so whichever half does not match your machine runs emulated.
Docker Desktop arranges that itself; on plain Linux you may need
`docker run --privileged --rm tonistiigi/binfmt --install all` once. If `web`
logs `exec format error`, that is this.

`./run status` shows what is up.

The passwords are the seeded accounts (published in the SUT's own README) and the
local database.
They are supplied from the environment rather than committed, because a rule
relaxed for values that do not matter is not in place for the ones that do. Skip
the step and the next Gradle invocation fails immediately, naming both keys and
the three ways to supply each — before any test runs, so nothing looks like a
product bug. See [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Running a subset

Tests are tagged by layer, depth and domain. Selection is one property carrying
a [JUnit tag expression](https://docs.junit.org/current/user-guide/#running-tests-tag-expressions):

```sh
./run test -Ptags=smoke
./run test -Ptags="ui & checkout"
./run test -Ptags="regression & !slow"
./run test -Ptags="api | contract"
```

**Quote any compound expression.** `&` and `|` are shell operators.

Thirteen tags, applied as annotations rather than strings, so `@Smoek` does not
compile: `@Ui` `@Api` `@Db` `@Contract` `@A11y` · `@Smoke` `@Regression` ·
`@Storefront` `@Checkout` `@Admin` `@Auth` · `@Slow` `@Quarantine`.

CI's blocking run is `-Ptags='!quarantine & !a11y'`, with accessibility as its
own step.

A tag expression that matches nothing **fails the build**. That is deliberate —
a selection that runs nothing must never report green, and unlike a red build it
gives no signal that anything is wrong.

Retries are off. A flaky test is `@Quarantine` plus a linked issue, never a
retry — and so is a known defect in the application under test, of which there is
currently one: see `CheckoutDefectUiTest`. The blocking run is
`-Ptags='!quarantine'`. Full vocabulary and reasoning:
[`docs/TAGS.md`](docs/TAGS.md).

`@Db` marks the tests that need a reachable database or mailbox. Exclude them for
a target that has neither: `./run test -Ptags='!db'`.

## Targets

The same bytecode runs against three targets. Only configuration changes.

| Profile | Target |
|---|---|
| `local` | Docker on localhost |
| `ci` | the same containers on a runner |
| `hosted` | practicesoftwaretesting.com |
| `buggy` | with-bugs.practicesoftwaretesting.com — a different build, see below |

```sh
./run test -Dtoolshop.profile=hosted
```

The API suite is green against `local` and `hosted` from the same bytecode, with
no recompile. It is **not** green against `buggy`: that deployment turned out to
be an older API contract rather than sprint 5 with faults introduced, so 31 of 41
tests fail there. Detail in [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

Precedence, highest first: `-D` system properties, environment variables,
`.env.local` (gitignored, local only), `application-<profile>.properties`,
`application.properties`. A missing required value aborts the run before any
test executes, naming the key and every way to supply it — as does a key that
overrides nothing, such as a mistyped `-D`.

Full key list, every validation rule and the reasoning:
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## What the checkout slice covers

One purchase, followed through every layer, because each can disagree with the
others:

```
API      an account and a product, created through the application
UI       storefront -> cart -> sign in -> address -> payment
DB       the invoices row, its line, and the total
mail     the confirmation the customer actually received
```

That last pair is not decoration. On this application a single press of "finish"
shows a payment success message and creates **no order** — a suite that asserted
on the message would report it as passing. See
[`docs/adr/0005`](docs/adr/0005-failure-evidence-is-attached-by-one-extension.md).

## What the API contract check found

Every API call is validated against the application's own OpenAPI document — a
filter on the shared client, so no test can skip it. Turning it on failed 28 of
41 tests, from thirteen distinct disagreements, every one of them the document
under-reporting what the application answers: `POST /invoices` returns 201 where
the document lists only 200, every administrative route returns an undocumented
403, and a locked account returns an undocumented 423.

Each is declared individually in a whitelist, and a test asserts each is still a
deviation — so when one is documented properly, the build says the entry can go.
[`docs/adr/0007`](docs/adr/0007-contract-validation-runs-on-every-call.md).

## Accessibility

WCAG 2.0 A and AA, asserted against a per-page baseline rather than against
zero, so a new violation and a fixed one both fail.
[`docs/adr/0008`](docs/adr/0008-accessibility-is-asserted-against-a-baseline.md).

## Failure evidence

Every UI failure attaches a screenshot, the Playwright trace, the page URL, the
failed requests and the browser console — automatically, with nothing for a test
to remember. The trace opens inside the Allure report.

## Layout

```
core/         config, tags, lifecycle extensions
api-tests/    REST Assured
ui-tests/     Playwright page objects
data/         database and mail verification
scripts/      readiness check
docs/         architecture, configuration, tags, threading, decision records
build-logic/  the one convention plugin
.run/         shared IntelliJ run configurations
run           the entry point CI also calls
```

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — modules, and a recorded reason per dependency
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) — layers, keys, profiles, and what is rejected
- [`docs/TAGS.md`](docs/TAGS.md) — the tag vocabulary, selection, and the empty-selection guard
- [`docs/THREADING.md`](docs/THREADING.md) — the parallelism model, per module, and why
- [`docs/SELF-ASSESSMENT.md`](docs/SELF-ASSESSMENT.md) — what worked, what didn't, measured
- [`docs/adr/`](docs/adr/) — decisions, each naming the alternative rejected and why
