# 6. CI runs the same commands as a developer, against a pinned application

Status: accepted

## Context

In the suite this framework was written against, the pipeline definitions held
orchestration that existed nowhere else: environment setup, service readiness,
which suites ran. None of it could be run locally, so the first time any of it
was exercised was when it broke, in CI, on someone else's change.

Separately, the application under test was cloned at build time. Whatever the
clone was on became the version under test, which makes "it passes locally" an
unfalsifiable claim.

## Decision

**Every workflow step is `./run <something>` or a script in `scripts/`.** The
readiness poll, the data reset, the tag expression, the report and the job
summary are all in this repository. The workflow file chooses *when* things run
and supplies credentials; it contains no logic of its own.

**The application is pinned by digest in `docker/docker-compose.sut.yml`, in this
repository.** `./run up` uses it by default, so no clone of the application is
needed at all. `TOOLSHOP_SUT_DIR` still overrides it with a clone, for the case
of working on the application itself; nothing in CI sets it.

Three deliberate differences from the application's own compose files:

- **Digests, not tags.** `sprint5-api:2.4` is mutable and `web` publishes only
  `latest`, so a tag does not describe a version.
- **A MariaDB healthcheck, with the API waiting on it.** The application's
  compose files have none, so the API can start against a database that is not
  accepting connections and crash-loop.
- **MailCatcher is included.** It exists only in the application's
  `docker-compose.override.yml`, so a run built from its production compose would
  have no mailbox and the mail assertions would fail for a reason unrelated to
  the product.

## Verification

The claim is that CI needs nothing a developer cannot run. Checked by running the
workflow's own steps locally, with `.env.local` moved away so the credentials
came only from the environment, and `TOOLSHOP_PROFILE=ci`:

```
./run up
./run test -Ptags='!quarantine'      136 tests, green
./scripts/test-summary.sh
./run report
./run logs
./run down
```

Also checked against the pinned images rather than a source build of the
application: the same 136 tests, green.

**Not checked: the workflow running on GitHub.** Actions cannot be executed here,
so the YAML's action versions and expression syntax are unverified. Every *step*
is a command that has been run locally, which is the part this decision is about;
the first push is what verifies the wiring.

## Consequences

**Credentials are repository *variables*, not secrets.** They are published
fixture passwords for a demo application: they protect nothing, `secrets` are
unavailable to pull requests from forks, and the rule that matters — never in a
committed file, always from the environment — holds either way. A real
application's credentials would be secrets, and nothing about the configuration
layer would change.

**`/dev/shm` needs no adjustment.** The plan carried a note that Chromium
crashes on launch without a larger `/dev/shm` in containers. Tests run directly
on the runner rather than in a container job, so it does not apply. If a
container job is ever introduced, it does.

**Allure is an uploaded artifact rather than a GitHub Pages site.** Pages was in
the plan for two reasons, and measurement removed one of them: the trace viewer
was expected to need a publicly reachable URL, and it does not — Allure loads
the trace from the report's own files. What remains is convenience, which does
not justify a step that silently does nothing until Pages is enabled on the
repository. The artifact works on the first run with no repository
configuration.

**The quarantined tests get a scheduled job, and it never blocks.** They are
excluded from every blocking run by definition, so without a job of their own
they would stop being looked at. `continue-on-error` because they are
known-failing: the job exists to make their status visible, not to stop a merge.
