# Architecture

## Modules

```
build-logic/   one convention plugin, applied by every module
core/          configuration, the tag vocabulary, lifecycle
api-tests/     the REST Assured client (src/main) and the API tests (src/test)
ui-tests/      Playwright page objects
data/          database and mail verification
```

`api-tests/src/main` is not a mistake. The API client is framework code with a
second consumer already planned: UI and database tests create their test data
through the API rather than by writing rows, so the application's own validation
and side effects apply and the data is shaped the way the product shapes it.

## Dependencies, and why each one is here

The project brief requires a recorded reason per dependency. "It is what people
use" is not one.

| Dependency | Why | Rejected alternative |
|---|---|---|
| `org.junit:junit-bom` | Makes the versionless catalog entries resolve, and constrains `platform-*` artifacts nothing names directly. Jupiter and platform release in lockstep and mixing them is genuinely broken. | Per-artifact versions. Not the reason first written down — see `SELF-ASSESSMENT.md`. |
| `org.junit.jupiter:junit-jupiter` | The test framework. | — |
| `org.junit.platform:junit-platform-launcher` | Mandatory on Gradle 9, which removed automatic test-framework loading. Also the interface the fail-fast configuration check implements. | — |
| `org.assertj:assertj-core` | One assertion vocabulary, so one failure format. A bare `assertTrue` prints nothing but `expected true`; the diagnostic value is the whole point. | JUnit's own assertions; Hamcrest via REST Assured's `.then()`. Both would mean two formats in one report. |
| `com.microsoft.playwright:playwright` | Browser automation with auto-waiting, tracing, and `getByTestId` as a first-class locator. The last replaces a locator strategy, not a library. | Selenium. Would reintroduce explicit waits, which the brief forbids. |
| `io.rest-assured:rest-assured` | Request specifications shared across a suite, and a filter mechanism that applies to every call. That second one is load-bearing: contract validation against the OpenAPI spec is applied as a filter in the shared client, so it cannot be opt-in. | `java.net.http.HttpClient`. Adequate for requests; has no equivalent of a filter every call passes through, so a per-call convention would be the only option. |
| `com.fasterxml.jackson.core:jackson-databind` | REST Assured declares Jackson **optional**, so typed payloads silently have no object mapper — measured: the resolved classpath had none. Payload records need one. | `Map.of("emial", …)` for request bodies. The same silent-typo failure the composed tag annotations exist to remove. A hand-rolled record serialiser is not "a few lines" once nesting, nulls and escaping are handled. |
| `org.mariadb.jdbc:mariadb-java-client` | Reading the rows a checkout wrote, to confirm the storefront's success message corresponds to an order. `PreparedStatement` only. | Trusting the API's own read endpoints. They are served from a cache, so they can agree with a test and disagree with the database — which is exactly the failure this catches. |
| `io.qameta.allure:allure-bom` + `allure-jupiter` | A behaviour tree — epic, feature, story, severity — from the first test, so the report has a top to its hierarchy on the day it is first generated rather than a flat list of class names. | `allure-junit5` (a relocation stub); Gradle's own HTML report (no behaviour hierarchy, no attachments). |

No HTTP or JSON library was added for the mail catcher: `java.net.http.HttpClient`
is in the JDK and Jackson was already here.

## Assertions: AssertJ, with one exception

AssertJ is the only assertion vocabulary for **values** — API responses, database
rows, email bodies, configuration. One failure format, one set of diagnostics.

Playwright's `assertThat(Locator)` is used for **page state**, and that is not
drift. AssertJ on `locator.textContent()` is a snapshot of a page that may still
be rendering, and the only way to make it reliable is a sleep — which the brief
forbids, and which would encode a guess about the application's timing.
Playwright's assertions retry until the condition holds, so the condition is what
gets written down. The split is by what is being asserted, not by who is
asserting, and it is visible in the imports of every UI test.

Deliberately absent, with the reason recorded so nobody adds them helpfully:

- **`org.junit.platform:junit-platform-suite`** — a `@Suite` class carries no
  `@Tag`, so it cannot compose with tag selection, and adding the aggregator runs
  every test twice unless engines are also filtered. Selection is `-Ptags`.
- **`org.hamcrest:hamcrest` as a direct dependency** — REST Assured brings it, and
  nothing here uses it. See AssertJ above.
- **A test-retry plugin** — a retry that passes on the second attempt reports
  green, so a test failing half the time looks healthy forever. `@Quarantine`
  plus a linked issue is the sanctioned answer.
- **A DI container** — JUnit extensions own all lifecycle. A second lifecycle
  owner is a seam, and the one thing that needs sharing across a run (the API
  client) lives in the launcher-session store, which is what that store is for.
- **`org.aspectj:aspectjweaver`** — needed only for Allure `@Step`, which is
  around-advice and requires a `-javaagent`. `@Epic`/`@Feature`/`@Story`/
  `@Severity` do not, and the report is legible without step decomposition.
- **The Allure Gradle plugin** — it adds dependencies and a javaagent to the
  build. `./run report` calls `npx allure@3`, which is a report generator and
  nothing else, pinned on the invocation.
- **Any third-party Gradle plugin** — including the Allure plugin, which adds
  dependencies and a javaagent that are a few lines when actually needed.

## Planned, not built

- **`perf/` (k6)** — performance work needs a controlled environment on a
  schedule. Numbers from a shared CI runner measure the runner.
- **`ai-tests/`** — an LLM evaluation harness.

## Rejected outright

- **Visual regression.** Baseline churn plus macOS-versus-runner font rendering
  makes it the highest-maintenance option for the least signal on this
  application.

## Decision records

Each names the alternative it rejected and why. See [`adr/`](adr/).
