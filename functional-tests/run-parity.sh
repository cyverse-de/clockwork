#!/usr/bin/env bash
# Run the identical functional suite against both implementations and report
# per-image pass/fail plus a diff of the recorded message envelopes.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

MAIN_TAG="${MAIN_TAG:-clockwork:main}"
GOLANG_TAG="${GOLANG_TAG:-clockwork:golang}"

rm -rf artifacts

echo "==> Starting broker"
docker compose up -d

run_suite() {
    local tag="$1" label="$2"
    echo
    echo "############ Suite vs $label ($tag) ############"
    CLOCKWORK_IMAGE="$tag" uv run pytest -v
}

run_suite "$MAIN_TAG" "main"
main_rc=$?
run_suite "$GOLANG_TAG" "golang"
golang_rc=$?

echo
echo "==> Comparing recorded message envelopes"
uv run python compare-envelopes.py
envelope_rc=$?

echo
echo "==> Tearing down broker"
docker compose down -v

result() { [ "$1" -eq 0 ] && echo PASS || echo FAIL; }

echo
echo "================ PARITY SUMMARY ================"
echo "main   ($MAIN_TAG):   $(result $main_rc)"
echo "golang ($GOLANG_TAG): $(result $golang_rc)"
case "$envelope_rc" in
    0) echo "envelopes:            IDENTICAL" ;;
    1) echo "envelopes:            DIVERGE (see diff above)" ;;
    *) echo "envelopes:            NOT COMPARED" ;;
esac

[ "$main_rc" -eq 0 ] && [ "$golang_rc" -eq 0 ]
