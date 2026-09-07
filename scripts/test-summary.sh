#!/bin/sh
# A pass/fail table from the JUnit XML that Gradle already writes.
#
# In this repository rather than in the workflow YAML on purpose: anything that
# exists only in a workflow cannot be run or debugged locally, which is the
# parity leak CLAUDE.md is about. CI appends the output to its job summary; a
# developer runs it to see the same table.
#
#   ./scripts/test-summary.sh                     # markdown to stdout
#   ./scripts/test-summary.sh >> "$GITHUB_STEP_SUMMARY"
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

python3 - "$HERE" <<'PY'
import glob, os, re, sys

root = sys.argv[1]
modules = {}

for path in glob.glob(os.path.join(root, "*", "build", "test-results", "test", "*.xml")):
    module = path.split(os.sep)[-5]
    with open(path, encoding="utf-8") as handle:
        head = handle.read(4000)

    def attr(name):
        found = re.search(rf'{name}="(\d+)"', head)
        return int(found.group(1)) if found else 0

    def seconds():
        found = re.search(r'time="([\d.]+)"', head)
        return float(found.group(1)) if found else 0.0

    totals = modules.setdefault(module, {"tests": 0, "failures": 0, "skipped": 0, "time": 0.0})
    totals["tests"] += attr("tests")
    totals["failures"] += attr("failures") + attr("errors")
    totals["skipped"] += attr("skipped")
    totals["time"] += seconds()

if not modules:
    print("No test results found. Did the suite run?")
    raise SystemExit(0)

grand = {key: sum(m[key] for m in modules.values()) for key in ("tests", "failures", "skipped")}

print(f"## {'Failed' if grand['failures'] else 'Passed'}: "
      f"{grand['tests'] - grand['failures'] - grand['skipped']} of {grand['tests']} tests")
print()
print("| Module | Tests | Failed | Skipped | Time |")
print("|---|---:|---:|---:|---:|")
for module in sorted(modules):
    totals = modules[module]
    print(f"| `{module}` | {totals['tests']} | {totals['failures']} | {totals['skipped']} "
          f"| {totals['time']:.1f}s |")
print()

# Name the failures. A count alone sends the reader to the artefacts to find out
# what a one-line summary could have told them.
failures = []
for path in glob.glob(os.path.join(root, "*", "build", "test-results", "test", "*.xml")):
    with open(path, encoding="utf-8") as handle:
        content = handle.read()
    for case in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>(.*?)</testcase>',
                            content, re.S):
        if re.search(r"<(failure|error)", case.group(3)):
            failures.append(f"{case.group(2).split('.')[-1]}.{case.group(1)}")

if failures:
    print("### Failures")
    for name in sorted(failures):
        print(f"- `{name}`")
PY
