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

## Measurements

| Claim | How it was measured | Result |
|---|---|---|
| Gradle's `failOnNoDiscoveredTests` guards empty tag selections | one-test module, zero-match tag filter, Gradle 9.7.1 | **False.** See `docs/adr/0003`. |

## What I would do differently
_(to write)_
