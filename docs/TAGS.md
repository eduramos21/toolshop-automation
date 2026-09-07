# Tags and test selection

Twelve tags, applied as annotations, composed into a JUnit tag expression on the
command line. A selection that matches nothing fails the build.

## The vocabulary

| Group | Tags | |
|---|---|---|
| Layer | `@Ui` `@Api` `@Db` `@Contract` `@A11y` | which surface the test drives |
| Depth | `@Smoke` `@Regression` | how much of the suite a run pays for |
| Domain | `@Storefront` `@Checkout` `@Admin` `@Auth` | which part of the product |
| Escape hatch | `@Slow` `@Quarantine` | admissions, kept visible |

```java
@Api
@Smoke
@Checkout
class CartApiTest {

    @Test
    @Slow
    void aCartSurvivesTwentyItems() { }
}
```

A layer tag also selects a module, because there is one layer per module.
`-Ptags=api` therefore runs nothing in `ui-tests`, which is correct and is why
the empty-selection guard is per-invocation rather than per module.

## Annotations, not `@Tag("string")`

`@Smoek` does not compile. `@Tag("smoek")` does, and produces a test that
belongs to no suite — which reads identically to a passing test in every report.
That is the whole reason this package exists.

The annotations are backed by constants in `Tags`, which tests never reference.
`TagVocabularyTest` checks the two never drift apart, and checks the two ways a
composed annotation can be silently inert: a `@Tag` value that does not match
the annotation's own name, and a missing `RUNTIME` retention.

## Selecting

`-Ptags` carries a
[JUnit tag expression](https://docs.junit.org/current/user-guide/#running-tests-tag-expressions).

```sh
./run test                              # everything
./run test -Ptags='!quarantine'         # what CI runs on a pull request
./run test -Ptags=smoke
./run test -Ptags='ui & checkout'
./run test -Ptags='regression & !slow'
./run test -Ptags='api | contract'
```

**Quote any compound expression.** `&` and `|` are shell operators.

An untagged test is still selected by `!quarantine`, so forgetting a depth tag
does not silently drop a test out of the blocking run. Forgetting it does drop
the test from `-Ptags=smoke`, which is the intended asymmetry: the blocking run
is inclusive by default and the fast run is opt-in.

## A selection that runs nothing fails

```
$ ./gradlew test -Ptags='smoke & admin'

* What went wrong:
Execution failed for task ':verifyTestSelection'.
> -Ptags="smoke & admin" selected no tests. Nothing ran.
```

`verifyTestSelection` lives in the root build file, finalises every `Test` task,
and is only wired into the graph when `-Ptags` is present. It sums executed
tests across every module and fails on zero.

It exists because Gradle's own guards do not cover this. Both
`failOnNoDiscoveredTests` and `filter.isFailOnNoMatchingTests` are on, and
neither fires for a zero-match tag filter — tag filtering happens after
discovery, so the class is discovered and then removed, and "no tests
discovered" is false. Measured; see
[`adr/0003`](adr/0003-empty-test-selection-must-fail.md).

Its failure message lists the vocabulary by reading the annotation file names in
`core`, so there is no second copy of the list in the build to fall out of date.
`TagVocabularyTest` is what keeps a file name and the tag it declares in step.

## Flaky tests: `@Quarantine`, never a retry

A retry that passes on the second attempt reports green. A test failing half the
time then looks healthy indefinitely, and the race underneath it is never found —
so retries are off and stay off.

The sanctioned answer is `@Quarantine` with a linked issue and an owner in a
comment:

```java
@Test
@Quarantine   // #42, races with the cart badge animation - owner: <name>
void theCartBadgeCountsItems() { }
```

Pull requests run `-Ptags='!quarantine'`. A scheduled job runs
`-Ptags=quarantine`, so a quarantined test stays visible rather than becoming
permanently ignored.

It is also the right mark for a **known defect in the application under test**,
which is what the one quarantined test here is. `CheckoutDefectUiTest` asserts
that a single press of "finish" places an order; it does not, and the
application is third-party so it cannot be fixed here. The alternatives were
worse: asserting the broken behaviour turns a defect into a requirement and goes
red when it is fixed, and deleting the test loses the finding. Quarantine says
what should happen and marks it known.

`@Contract` marks the tests that guard the OpenAPI whitelist. Note that contract
validation itself is not confined to them: it is a filter on the shared client,
so every API call in every test is validated. These tests assert that each
*known* deviation is still a deviation, so the whitelist cannot outlive its
reasons. See [`adr/0007`](adr/0007-contract-validation-runs-on-every-call.md).

`@A11y` is its own suite, asserted against a per-page baseline rather than
against zero. See [`adr/0008`](adr/0008-accessibility-is-asserted-against-a-baseline.md).
The blocking run excludes it — `-Ptags='!quarantine & !a11y'` — so its failures
are attributable, but it still runs on every push.

`@Db` marks the tests that need a reachable database or mailbox — the local and
CI targets. A completed order cannot be undone through the API, so a checkout
test can only clean up after itself where the database is reachable. For a target
without one: `./run test -Ptags='!db'`.

## IDE run configurations, and their honest limit

Four shared Gradle configurations are committed in `.run/`: **All tests**,
**Blocking run (no quarantine)**, **Smoke**, **Checkout slice**.

They are *Gradle* configurations, so they carry `-Ptags` and behave exactly as
the command line does.

**They do not affect a gutter run.** Clicking the arrow beside a test method
runs it through the IDE's own JUnit runner, not through Gradle, so nothing a
`Test` task sets exists in that JVM and no `-Ptags` applies. That is a fact
about the IDE, not a setting to find.

It is also why the configuration layer is built the way it is: the build only
ever *overrides*, never *supplies*, so the default configuration needs nothing
injected and a gutter run works with no setup. See
[`adr/0002`](adr/0002-gradle-never-owns-configuration.md) and
[`CONFIGURATION.md`](CONFIGURATION.md).

The parallelism model is in `junit-platform.properties` per module — a test
resource, so a gutter run and a Gradle run use the same one. See
[`THREADING.md`](THREADING.md).
