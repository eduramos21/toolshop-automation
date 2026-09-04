#!/bin/sh
# Polls the Toolshop API until it reports healthy, or fails with a message that
# says what was tried and for how long.
#
# Called by ./run (both `up` and `test`) and by the CI workflow. It exists in
# exactly one place on purpose: readiness logic duplicated into a workflow YAML
# is a local/CI parity leak.
#
# There is no fixed sleep here. The poll interval is a retry cadence bounded by
# a deadline, not an assumption about how long startup takes.
set -eu

URL="${1:-${TOOLSHOP_API_BASE_URL:-http://localhost:8091}/status}"
TIMEOUT="${TOOLSHOP_READY_TIMEOUT:-120}"
INTERVAL=2

deadline=$(( $(date +%s) + TIMEOUT ))
attempt=0

while :; do
    attempt=$(( attempt + 1 ))
    body=$(curl -sS -m 5 "$URL" 2>/dev/null) && case "$body" in
        *'"app_name"'*) printf '%s is healthy after %ss (%d attempts): %s\n' \
                            "$URL" "$(( $(date +%s) - deadline + TIMEOUT ))" "$attempt" "$body"
                        exit 0 ;;
    esac

    if [ "$(date +%s)" -ge "$deadline" ]; then
        printf '\n%s did not become healthy within %ss (%d attempts).\n\n' \
               "$URL" "$TIMEOUT" "$attempt" >&2
        printf 'Last response: %s\n\n' "${body:-<no response>}" >&2
        printf 'Check that the SUT is running:\n' >&2
        printf '  ./run up\n' >&2
        printf '  docker ps --filter name=pst-\n\n' >&2
        printf 'A healthy response looks like:\n' >&2
        printf '  {"version":"5.0","environment":"local","app_name":"Toolshop"}\n' >&2
        exit 1
    fi

    sleep "$INTERVAL"
done
