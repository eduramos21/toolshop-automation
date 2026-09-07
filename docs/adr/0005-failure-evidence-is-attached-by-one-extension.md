# 5. Failure evidence is attached by one extension, and traces render inline

Status: accepted — with the enabling behaviour measured

## Context

The suite this framework was written against captured failure evidence through a
field each test set by hand: `testResult = "SUCCESS"` at the end of the test
body, read by an `@AfterEach` that attached a screenshot when it was anything
else. 258 call sites. About fifteen were missing or wrong, so those tests
attached a screenshot on every passing run, and the failures that most needed
evidence had none.

A convention applied 258 times is a convention that is applied 243 times. The
fix is not more discipline; it is removing the call site.

Separately, Playwright's trace is by far the most useful artefact — actions, DOM
snapshots, network and console in one timeline — and it is worthless if opening
it means downloading a zip and finding somewhere to load it.

## Decision

One `FailureEvidence` extension, registered by `ServiceLoader`, applying to every
UI test with nothing to remember and nothing to extend. On failure it attaches:

| Attachment | Media type |
|---|---|
| Screenshot | `image/png` |
| Playwright trace | `application/vnd.allure.playwright-trace` |
| Page URL at failure | `text/plain` |
| Failed requests (4xx/5xx only) | `text/plain` |
| Browser console and page errors | `text/plain` |

## Measurements

**The evidence is not all available at one moment.** A screenshot needs an open
page; the trace needs a closed context. So there are two hooks, and which one
gets what was measured rather than assumed:

- `afterTestExecution` runs while the page is open — the only place a screenshot
  can be taken, and where the console and network journals are read.
- The trace zip is written when the browser context closes, and
  `BrowserContextExtension` is itself a `TestWatcher` that closes it. Class-level
  extensions run before global ones on the way out, so by the time this
  extension's `testFailed` runs, `build/playwright/<class>.<method>-chromium/trace.zip`
  exists. Verified with a deliberately failing test: all five attachments were
  present, the trace among them.

**Allure 3 renders the trace inline — spike S3.** Generated a report from a real
failure and inspected it: the report's own bundle handles
`application/vnd.allure.playwright-trace`, the zip is copied to
`data/attachments/` and marked `used`, and the report loads it into the
Playwright trace viewer rather than offering it as a download.

One caveat worth knowing: the viewer *code* is served from
`trace.playwright.dev`, so viewing a trace needs internet access even though the
trace itself never leaves the report. Neither fallback in the plan — an HTML
attachment iframing the viewer, or a link-out — is needed.

**Allure 3 reads allure-java 2.x results — spike S4.** `allure@3` is 3.16.1 with
`generate`, `open` and `quality-gate`, configured by `allurerc.mjs`. It generated
a complete report, behaviour tree included, from results written by
`allure-jupiter:2.35.5`. No migration, and the Allure 2 CLI fallback is not
needed.

## Consequences

**Video is deliberately not recorded.** The plan called for it. Playwright
records video for every test in order to attach it to the few that fail, and the
trace already carries screenshots, DOM snapshots, console and network for exactly
those. Having measured that the trace renders inline, video would cost every run
something to duplicate what is already there. If the trace viewer ever stops
being reachable, `contextOptions.setRecordVideoDir` is the fallback.

**The results directory is a declared task output.** Without that it
accumulates across runs, and a report then shows one test once per run it has
ever had - six copies of a single quarantined test, which reads as flakiness in
something that only ever ran once per invocation. Declaring
`build/allure-results` as an output of the `Test` task makes Gradle remove the
previous run's results when the task re-executes, which is the same mechanism
that already keeps the JUnit XML honest.

**The report covers the product tests, not the framework's own.** `core` and
`data` hold unit tests of the configuration loader, the tag vocabulary and the
mail parsing; they carry no Allure dependency and appear in no behaviour tree,
because they are not behaviours of the application. 51 of the 136 tests are in
the report by design.

**A parameterised failure's trace is the last invocation's.** Playwright names
the output directory after the test method, not the invocation, so all cases of
an `@EnumSource` test write to one path and overwrite each other. Recorded rather
than worked around: the screenshot and logs are still per-case, and splitting the
directory would mean reimplementing Playwright's naming.

**The extension imports `com.microsoft.playwright.impl.junit.PageExtension`.**
An `impl` package, and the coupling is accepted knowingly:
`getOrCreatePage(ExtensionContext)` is public and static and is the only way for
an extension to reach the current page. The alternative is every test handing it
over, which is the convention being deleted. Playwright is pinned to an exact
version, so this cannot drift without the build changing.

**Retries stay off.** A retry that passes on the second attempt reports green, so
a test failing half the time looks healthy indefinitely and the race underneath
it is never found. `@Quarantine` plus a linked issue is the sanctioned answer.

Rejected: the Allure Gradle plugin. It adds dependencies and an AspectJ
javaagent to the build; the only thing needed here is a report generator, which
`npx allure@3` is, pinned on the invocation by `./run report`.
