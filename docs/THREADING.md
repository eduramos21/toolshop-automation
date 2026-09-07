# Threading

One knob per module, in a test resource, so an IDE run and a Gradle run use the
same model.

## The model

| Module | Classes | Methods | Strategy |
|---|---|---|---|
| `ui-tests` | concurrent | **same thread** | `fixed`, parallelism 4 |
| `api-tests` | concurrent | concurrent | `dynamic`, factor 3 |
| `core` | — | — | sequential |
| `data` | — | — | sequential; its only tests are pure unit tests |

Configured in each module's `src/test/resources/junit-platform.properties`.
`core` has no file: 73 fast in-process unit tests are not worth the scheduling.

Gradle's `maxParallelForks` is pinned to **1** in the conventions plugin.
Forking multiplies browser processes and throws away Playwright's per-thread
reuse, so there is deliberately one concurrency mechanism and not two competing
ones.

## Why `ui-tests` is class-level only

Not a tuning choice. `@UsePlaywright` caches `Playwright` and `Browser` per
**thread**, and the `afterAll` it installs closes only the calling thread's
browser. Concurrent methods inside one class therefore leak one browser process
per thread for the lifetime of the JVM.

Class-level parallelism is safe because a class runs entirely on one thread, so
the browser it opened is the browser that gets closed.

Two further consequences of the same caching, both worth knowing before P5:

- `Options` is resolved per **class** and the cache is never invalidated when it
  changes. So there is exactly **one `OptionsFactory` per test JVM**:
  `browserName`, `channel`, `headless` and `launchOptions` cannot vary between
  classes in one run. Firefox coverage is a second `Test` task, not a second
  factory.
- `fixed` rather than `dynamic` for the parallelism count, because the number is
  one browser process each. It is bounded by memory, not by cores, and a hosted
  CI runner has less of both than a laptop.

## Why `api-tests` is method-level too

An API test is an HTTP round trip with no per-thread native resource behind it.
The limit is the SUT's throughput, not this JVM's memory, so this is the layer
that should be fast.

It requires what the data design requires anyway: test data created per test,
uniquely named, never depended on by another test. Method-level parallelism does
not create that requirement, it just makes breaking it fail loudly instead of
intermittently — which is the better outcome.

## `data`

Sequential, because the only tests in it are unit tests of the mail parsing. The
database and mail helpers are exercised from `ui-tests`, where the concurrency
question is about isolation between tests rather than threads — and that is
answered by every test buying as an account unique to it, not by a parallelism
setting.

A connection per query and no pool, deliberately: these are a handful of
verification queries at the end of a test, a shared `Connection` is not thread
safe, and a pool would be machinery in service of nothing measurable.

## What parallelism cost, measured

Two UI classes, class-level parallelism, identical start timestamps — so it
engages. `CheckoutUiTest` runs six full browser checkouts on one thread in about
100 seconds while `StorefrontUiTest` finishes four tests in four seconds on
another. The limit is that a class is the unit: a class with six slow tests
cannot be split, and splitting it is the only way to shorten that path.

## Overriding for CI

A constrained runner needs smaller numbers. It gets them on the invocation, not
from a branch in code:

```sh
./gradlew :ui-tests:test  -Djunit.jupiter.execution.parallel.config.fixed.parallelism=2
./gradlew :api-tests:test -Djunit.jupiter.execution.parallel.config.dynamic.factor=1
```

The conventions plugin forwards any `junit.*` system property on the Gradle
invocation into the test JVM, and JUnit resolves a system property ahead of
`junit-platform.properties`. So there is no `if (isCi())` anywhere, and the same
override works locally for reproducing a CI-only failure.

See [`adr/0002`](adr/0002-gradle-never-owns-configuration.md) for why forwarding
is the build's only role.
