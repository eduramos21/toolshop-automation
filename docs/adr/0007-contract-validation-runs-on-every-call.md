# 7. Contract validation runs on every call, with known drift declared

Status: accepted

## Context

The suite this framework was written against advertised OpenAPI contract
validation in its documentation. What existed was a private method with no
callers, no specification files, and no test that used it.

That is the failure mode worth designing against: contract validation that is
opt-in is contract validation that is opted out of, because the tests that need
it most are the ones nobody remembered to add it to.

## Decision

The validator is a REST Assured filter on the shared request specification. Every
call any test makes is validated. There is no way to write a test that skips it,
and a new test gets it without asking.

The document is fetched from the running application at `/docs` rather than
committed here. A committed copy is a contract this repository asserts against
itself, and it goes stale silently.

## Measurements

**The document is OpenAPI 3.2, and no Java validator supports 3.2.**
swagger-parser does not recognise the version and falls back to Swagger 2
parsing, which then rejects `content` and `requestBody` as "unexpected" on every
operation in the file. Setting the version to 3.1.0 leaves exactly one
complaint: seven paths declare a `query` operation. `QUERY` is an HTTP method
OpenAPI 3.2 added, and the application really does use it — the storefront sends
`QUERY /products` — so this is a genuine 3.2 document, not a mislabelled one.

So the document is normalised in two ways and no others: the version is set to
`3.1.0`, and the `query` operation is removed from the seven paths that have
one. Both are removals of things this suite does not exercise; `/products` keeps
its `get`, which is the operation the suite calls. `ApiContractTest` asserts
exactly which operations were dropped, so the cost of the normalising cannot
grow unnoticed.

**Turning it on failed 28 of 41 API tests.** Thirteen distinct disagreements, all
of them the document under-reporting what the application answers:

| Deviation | |
|---|---|
| `POST /invoices` answers 201 | document lists only 200 |
| `POST /users/login` answers 401 | not listed |
| `POST /users/login` answers 423 for a locked account | not listed |
| `POST /users/register` answers 422 | not listed |
| `GET /users` and all seven `/reports/*` answer 403 | document lists 401, not 403 |
| Seven responses carry a body | document declares them empty |
| Two responses return properties absent from their schema | |

The 403 group is the most interesting. 401 and 403 are answers to different
questions — "I do not know who you are" against "I know exactly who you are and
no" — and a client written from this document would have no reason to handle the
second.

**Requests are not validated, only responses.** Half the value of the API suite
is in its negative cases: a registration missing every required field, a payment
method the API does not offer. Those requests are *supposed* to violate the
schema, and validating them turns every negative test red for being negative.

## Consequences

**Known drift is a whitelist, declared entry by entry.** Not a level change, not
a suppressed message key applied broadly — one named rule per deviation, each
naming the path and status it forgives. A blanket suppression would have hidden
the 423 case, which was not in the first measurement because no account happened
to be locked during it and which failed the very next run.

**The whitelist is guarded by tests.** A list of known problems is only useful
while something keeps it accurate, so `ApiContractTest` replays each interaction
against a validator with *no* whitelist and asserts the deviation is still
there. When the application documents one of these properly, that test fails and
says the entry can go. Without this the whitelist would be a graveyard.

**Anything new fails immediately.** That is the point of leaving the filter on
globally rather than confining validation to the contract suite: the 28 failures
were free, and the fourteenth deviation will be too.

Rejected: validating against a committed copy of the document. It would have
avoided the 3.2 problem, by testing against a contract this repository wrote for
itself.
