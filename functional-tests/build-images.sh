#!/usr/bin/env bash
# Build the two clockwork images the parity suite runs against, each from a
# detached git worktree so the build context is exactly the committed branch
# (the uncommitted functional-tests/ directory never pollutes the context).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"

MAIN_TAG="${MAIN_TAG:-clockwork:main}"
GOLANG_TAG="${GOLANG_TAG:-clockwork:golang}"
MAIN_REF="${MAIN_REF:-origin/main}"
GOLANG_REF="${GOLANG_REF:-origin/golang}"

cd "$REPO_ROOT"
git fetch --quiet origin main golang

build_from_ref() {
    local ref="$1" tag="$2"
    local wt
    wt="$(mktemp -d)"
    echo "==> Building $tag from $ref"
    git worktree add --quiet --detach "$wt" "$ref"
    # shellcheck disable=SC2064
    trap "git worktree remove --force '$wt' >/dev/null 2>&1 || true; rm -rf '$wt'" RETURN
    docker build -t "$tag" "$wt"
}

build_from_ref "$MAIN_REF" "$MAIN_TAG"
build_from_ref "$GOLANG_REF" "$GOLANG_TAG"

echo "==> Built $MAIN_TAG and $GOLANG_TAG"
