# 8. Accessibility is asserted against a baseline, not against zero

Status: accepted

## Context

The suite this framework was written against had 273 UI tests and no
accessibility checks at all. Adding them is cheap — axe-core runs in the browser
a UI test already has — so the question is not whether to scan but what to
assert.

## Decision

`@A11y` is its own layer tag and its own suite. Each page declares exactly the
rules it currently violates, and the test asserts the scan result equals that
set.

Scanned against WCAG 2.0 A and AA. Every violation is attached to the report
with its affected nodes, so a failure names elements rather than a rule id to go
and look up.

## Why a baseline

**Asserting zero would be red from the day it was written.** A suite that is
always red is a suite nobody reads, and its failures stop meaning anything.

**Asserting "no more than before" lets both directions hide.** A fix goes
unnoticed, and a regression can hide behind an unrelated fix as long as the
count does not rise.

Equality catches both. A new violation fails. A *fixed* violation also fails,
saying the baseline is stale. Same shape as the OpenAPI whitelist in
[`adr/0007`](0007-contract-validation-runs-on-every-call.md), for the same
reason: a list of known problems is only useful while something forces it to
stay accurate.

## Measurements

Measured 2026-09, WCAG 2.0 A and AA:

| Page | Violations |
|---|---|
| Storefront | `list` (serious, 3 nodes) |
| Sign in | `button-name` (**critical**) |
| Register | `button-name` (**critical**), `list` (serious) |
| Product detail | none |
| Contact | none |
| Checkout, cart step | none |

`button-name` is a button with no accessible name: a screen reader announces it
as "button" and nothing else, on the two pages where someone signs in or creates
an account.

**The scan has to wait for the page to finish loading, and that is not a
detail.** A first pass without waiting reported *zero* violations on the sign-in
page. With `waitForLoadState(NETWORKIDLE)` it consistently reports the critical
one — a lazily-rendered control had not been given its label yet when the
unwaited scan ran.

An accessibility scan that under-reports is worse than one that does not run,
because it produces a green tick. Playwright discourages `NETWORKIDLE` for
waiting on a specific element, and rightly — but "the page has finished loading
everything" is precisely the precondition for scanning a whole page, and it is a
condition rather than a guess at a duration.

## Consequences

**It runs in CI as its own step, and it blocks.** A deliberate departure from
the plan, which said to run accessibility separately so violations would not
block. That assumed asserting zero — permanently red, and therefore ignored.
Against a baseline, a failure means a violation appeared or one was fixed, and
both are worth stopping for.

It is still a separate step rather than part of the functional run, so its
failures are attributable and do not mix with product failures. It shares the
job, because it needs the same application and the same browser and paying for
both twice to separate two tag expressions is not worth it.

**The baseline is a commitment to maintain.** Every entry has to be re-measured
when a page changes. That is the cost of the assertion being exact, and it is
paid in one file.
