# 1. Configuration lives in .properties, not .yml

Status: accepted

## Context

The project brief originally illustrated the config layer with `application-ci.yml`.
The framework must resolve configuration in a strict precedence: system
properties, then environment variables, then a profile file, then defaults.

A large Java test suite I assessed before starting this one had a bug in
exactly this area: a `.env` loader in `build.gradle` called `System.setProperty`,
which runs *after* the command line is parsed, so `.env` silently overrode `-D`
flags — while the project's own documentation described the opposite precedence.
Because the Gradle daemon is long-lived, a stale value also survived across
builds in the same daemon.

## Decision

Profile files are `.properties`. Keys are dotted, lowercase, hyphen-separated:
`toolshop.api.base-url`.

## Consequences

The dotted key *is* the `-D` name. `toolshop.api.base-url` in a file and
`-Dtoolshop.api.base-url` on the command line are the same string, so merging
the layers is a `Map` merge with no name transformation.

That matters because name transformation is where that class of bug lives: any
translation step between a file's key shape and a system property's key shape
is a place where "the override silently didn't apply" can hide. YAML would
require inventing a nested-path-to-dotted-key convention and writing that
translation.

Secondary: `java.util.Properties` is in the JDK. snakeyaml is a dependency with
a CVE history that hands back a `Map<String, Object>` tree needing flattening
into the dotted keys we wanted anyway. The project brief requires a recorded
reason per dependency and there is not one here.

The config genuinely is flat: base URLs, timeouts, a JDBC URL, a profile name.

Rejected: YAML. Cost is a dependency plus a translation layer; benefit is
nesting we do not need.

The project brief was amended to match.
