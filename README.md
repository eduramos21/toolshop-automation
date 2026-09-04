# toolshop-automation

A test automation framework — UI, API, database and contract layers — built
against the [Toolshop](https://github.com/testsmith-io/practice-software-testing)
demo application, running identically on a laptop and in GitHub Actions.

Java 21 · Gradle 9.7.1 · JUnit 6 · Playwright · REST Assured · Allure

## Quick start

```sh
git clone https://github.com/testsmith-io/practice-software-testing   # next to this repo
./run up            # start the SUT, wait for it, seed it
./run test          # run the suite
```

`./run status` shows what is up.

The SUT is expected at `../practice-software-testing`. Point `TOOLSHOP_SUT_DIR`
elsewhere if yours lives somewhere else.

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

A tag expression that matches nothing **fails the build**. That is deliberate —
a selection that runs nothing must never report green.

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
test executes, naming the key and every way to supply it.

## Layout

```
core/         config, tags, lifecycle extensions
api-tests/    REST Assured
ui-tests/     Playwright page objects
data/         database and mail verification
scripts/      readiness check
docs/adr/     decision records
run           the entry point CI also calls
```

## Docs

- [`docs/SELF-ASSESSMENT.md`](docs/SELF-ASSESSMENT.md) — what worked, what didn't, measured
- [`docs/adr/`](docs/adr/) — decisions, each naming the alternative rejected and why
