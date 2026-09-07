# Configuration

One suite, three targets, no recompile. A target is a profile; it is never a
branch in code.

## Setup, once per clone

```sh
cp .env.local.example .env.local     # then fill in the two passwords
```

The passwords are the seeded Toolshop accounts, published in
[the application's own README](https://github.com/testsmith-io/practice-software-testing).
They are still supplied from the environment rather than committed. A rule that
is relaxed for the values that do not matter is not in place for the ones that
do — and there is no value a secret key can hold in a committed file that is
better than the absence of one.

Without them, any Gradle invocation that runs tests fails immediately, naming
both keys and the three ways to supply each. It fails before test discovery, so
no test runs and nothing looks like a product bug.

## Precedence

Highest wins.

| | Layer | Key shape | Where it comes from |
|---|---|---|---|
| 1 | system properties | `toolshop.api.base-url` | `-Dtoolshop.api.base-url=...` on the Gradle invocation |
| 2 | environment variables | `TOOLSHOP_API_BASE_URL` | the shell, and how CI supplies secrets |
| 3 | `.env.local` | `TOOLSHOP_API_BASE_URL` | gitignored, local development only |
| 4 | `application-<profile>.properties` | `toolshop.api.base-url` | committed, one per target |
| 5 | `application.properties` | `toolshop.api.base-url` | committed defaults |

`.env.local` sits *below* real environment variables deliberately. CI sets
environment variables and has no such file, so the file cannot affect CI; and it
can never outrank a `-D`. It exists for one reason: with it in place an IDE
gutter run needs no run configuration, because nothing has to be injected by the
build.

The file is found by searching upward from the working directory, which differs
between a Gradle test task — the module directory — and an IDE gutter run.
Pinning the test task's working directory to the repository root would fix one
and break the other.

## Keys

| Key | Default | Notes |
|---|---|---|
| `toolshop.profile` | `local` | `local`, `ci`, `hosted`, `buggy`. Cannot be set from a properties file |
| `toolshop.ui.base-url` | per profile | absolute `http`/`https`, trailing slash stripped |
| `toolshop.api.base-url` | per profile | as above. The API has no route at `/`; health is `/status` |
| `toolshop.ui.timeout` | `PT30S` | ISO-8601, positive |
| `toolshop.api.timeout` | `PT10S` | ISO-8601, positive |
| `toolshop.ui.headless` | `true` | exactly `true` or `false` |
| `toolshop.admin.email` | seeded admin | |
| `toolshop.admin.password` | **none** | secret |
| `toolshop.customer.email` | seeded customer | |
| `toolshop.customer.password` | **none** | secret |
| `toolshop.db.url` | per profile | optional; JDBC URL, `local`/`ci` only |
| `toolshop.db.username` | per profile | optional; required if the URL is set |
| `toolshop.db.password` | **none** | optional **secret**; required if the URL is set |
| `toolshop.mail.base-url` | per profile | optional; the mail catcher, `local`/`ci` only |

### Optional keys, and why they are not "skip if missing"

The database and the mail catcher are containers on the same machine as a local
or CI run. Against the hosted instance there is no database to reach and no
mailbox to read, so those keys are absent there and the configuration still
resolves.

Optional does **not** mean a test quietly skips. A test that needs one of them
and cannot have it fails, naming the keys and the tag expression that excludes
it — a silently skipped test is the same green-with-no-signal outcome the
empty-selection guard exists to prevent, one level down:

```sh
./run test -Ptags='!db'      # a target with no reachable database
```

The database URL is what declares that a target has a database, so if it is set
the credentials are required too. That rule is anchored on the URL rather than
treated as a three-key group, and the difference was measured: a developer's
`.env.local` keeps `TOOLSHOP_DB_PASSWORD` set permanently, and a group rule made
every `hosted` run fail over a password nothing was going to use.

## Profiles

| Profile | UI | API |
|---|---|---|
| `local` | `http://localhost:4200` | `http://localhost:8091` |
| `ci` | same as local | same as local |
| `hosted` | `practicesoftwaretesting.com` | `api.practicesoftwaretesting.com` |
| `buggy` | `with-bugs.practicesoftwaretesting.com` | `api-with-bugs.practicesoftwaretesting.com` |

```sh
./run test -Dtoolshop.profile=hosted
```

`ci` duplicates `local` today and is kept as its own file anyway: CI is where the
values are most likely to need to differ, and the whole point of the layering is
that when they do it is a config change rather than a branch in code.

`buggy` needs a correction to what was first written here. The intent was a
target that stops the suite being self-confirming: a suite that only ever runs
against a healthy build demonstrates that it passes, not that it would notice.

Measured 2026-09, the hosted defect-injected deployment is **an older API
surface**, not sprint 5 with faults introduced — integer product ids instead of
ULIDs, `stock` instead of `in_stock`, and no `POST /carts` route at all — while
still reporting `"version":"5.0"`. 31 of 41 API tests fail against it. That is
the suite noticing a different contract, not the suite passing or failing a fair
test, so `buggy` is not a target the suite is expected to be green against.

The target that genuinely is the same version with defects injected is local:
`SPRINT=sprint5-with-bugs` on the same compose files. It serves the same URLs as
`local`, so it needs no profile of its own — the SUT changes, the configuration
does not.

## What is rejected, and why

The loader collects every problem and reports them together.

**A key containing an uppercase character.** `toolshop.api.baseUrl` maps to
`TOOLSHOP_API_BASEURL`, which cannot be mapped back to one key. Rejecting is
better than picking.

**An unrecognised `toolshop.*` system property.** A `-D` that overrides nothing
has the same effect as omitting it, so it has to be as loud. `-Dtoolshop.api.timeuot=PT5S`
is an error, not a silently ignored flag.

**An unrecognised key in a committed properties file.** Same reason.

**`toolshop.profile` set inside a properties file.** The active profile selects
which file is read, so it cannot be chosen from inside one.

**A secret key in any committed properties file** — including a profile this run
does not read. A placeholder parked in an unused profile is the same defect on a
delay.

**A secret that is set but empty.** `TOOLSHOP_ADMIN_PASSWORD=` is not a supplied
secret.

**A base URL that is not an absolute `http`/`https` URL with a host and an
in-range port.**

**A duration that is not a positive ISO-8601 duration.** `10s` is an error;
`PT10S` is the form.

**A boolean that is not exactly `true` or `false`.** `Boolean.parseBoolean` maps
every typo to `false`, so `-Dtoolshop.ui.headless=fasle` would open a browser
window on a CI runner and time out with no indication why.

Deliberately **not** rejected: an unrecognised `TOOLSHOP_*` environment
variable. `TOOLSHOP_SUT_DIR` and `TOOLSHOP_READY_TIMEOUT` belong to `./run` and
`scripts/wait-for-sut.sh`, and rejecting them would mean editing the loader to
add a shell variable. A mistyped `TOOLSHOP_*` name therefore goes unnoticed at
that point — but the key it was meant to supply is then missing, and that failure
names the exact variable to set.

## When it fails

```
Toolshop configuration is invalid. 2 problems:

  1. toolshop.admin.password is required and has no default anywhere. Supply it as
     -Dtoolshop.admin.password=<value>, or as TOOLSHOP_ADMIN_PASSWORD=<value> in the
     environment, which is how CI supplies it, or as the same variable in .env.local at
     the repository root, which is for local development only and can never outrank a -D.
  2. ...

Sources searched, highest precedence first:
  1  system properties                0 toolshop.* keys
  2  environment variables            0 TOOLSHOP_* variables, as seen by this JVM
  3  .env.local                       not found, searched upward from /repo/core
  4  application-local.properties     2 keys
  5  application.properties           5 keys

Active profile: local   (-Dtoolshop.profile=<profile>, default 'local', one of local, ci, hosted, buggy)

No test ran. This check happens before test discovery, so none of the above
is a product failure - it is this run's configuration.
```

The sources block is the part that earns its keep. A wrong profile, a
`.env.local` that is not where it was assumed to be, and a Gradle daemon holding
a stale environment all look identical from a missing value alone, and all three
are readable straight off the key counts.

## Where the code is

| | |
|---|---|
| `ToolshopConfig` | the immutable result, resolved once per JVM |
| `ConfigKey` | every recognised key, and which are secret |
| `ConfigLoader` | all five layers and every rule, in one class |
| `ConfigValidationListener` | runs the above before test discovery |

`ToolshopConfig.get()` is a static reader. That is how a run gets validated
before there is anything to inject into. Tests will receive the config by
constructor injection through a JUnit `ParameterResolver` when the extensions
land, rather than reaching for the static themselves.

The build never reads configuration and never calls `System.setProperty`; it
only forwards `toolshop.*` and `junit.*` system properties that already exist on
its own invocation. See
[`adr/0002`](adr/0002-gradle-never-owns-configuration.md).
