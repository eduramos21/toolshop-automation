# toolshop-automation

A test automation framework — UI, API, database and contract layers — built
against the [Toolshop](https://github.com/testsmith-io/practice-software-testing)
demo application, running identically on a laptop and in GitHub Actions.

Java 21 · Gradle 9.7.1 · JUnit 6 · Playwright · REST Assured · Allure

## Quick start

```sh
git clone https://github.com/testsmith-io/practice-software-testing   # next to this repo
cp .env.local.example .env.local                                     # then fill in two passwords
./run up            # start the SUT, wait for it, seed it
./run test          # run the suite
```

`./run status` shows what is up.

The SUT is expected at `../practice-software-testing`. Point `TOOLSHOP_SUT_DIR`
elsewhere if yours lives somewhere else.

The two passwords are the seeded accounts, published in the SUT's own README.
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

Twelve tags, applied as annotations rather than strings, so `@Smoek` does not
compile: `@Ui` `@Api` `@Db` `@Contract` · `@Smoke` `@Regression` ·
`@Storefront` `@Checkout` `@Admin` `@Auth` · `@Slow` `@Quarantine`.

A tag expression that matches nothing **fails the build**. That is deliberate —
a selection that runs nothing must never report green, and unlike a red build it
gives no signal that anything is wrong.

Retries are off. A flaky test is `@Quarantine` plus a linked issue, never a
retry. Full vocabulary and reasoning: [`docs/TAGS.md`](docs/TAGS.md).

## Targets

The same bytecode runs against three targets. Only configuration changes.

| Profile | Target |
|---|---|
| `local` | Docker on localhost |
| `hosted` | practicesoftwaretesting.com |
| `buggy` | with-bugs.practicesoftwaretesting.com |

```sh
./run test -Dtoolshop.profile=hosted
```

Precedence, highest first: `-D` system properties, environment variables,
`.env.local` (gitignored, local only), `application-<profile>.properties`,
`application.properties`. A missing required value aborts the run before any
test executes, naming the key and every way to supply it — as does a key that
overrides nothing, such as a mistyped `-D`.

Full key list, every validation rule and the reasoning:
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Layout

```
core/         config, tags, lifecycle extensions
api-tests/    REST Assured
ui-tests/     Playwright page objects
data/         database and mail verification
scripts/      readiness check
docs/         configuration, tags, threading, decision records
.run/         shared IntelliJ run configurations
run           the entry point CI also calls
```

## Docs

- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) — layers, keys, profiles, and what is rejected
- [`docs/TAGS.md`](docs/TAGS.md) — the tag vocabulary, selection, and the empty-selection guard
- [`docs/THREADING.md`](docs/THREADING.md) — the parallelism model, per module, and why
- [`docs/SELF-ASSESSMENT.md`](docs/SELF-ASSESSMENT.md) — what worked, what didn't, measured
- [`docs/adr/`](docs/adr/) — decisions, each naming the alternative rejected and why
