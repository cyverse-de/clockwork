#!/usr/bin/env python3
"""Diff the message envelopes recorded by each implementation under artifacts/.

Each suite run writes envelope-<image>.json. This compares them field by field
and reports any divergence (e.g. delivery mode differing between the Clojure and
Go implementations). Exits 0 when the recorded envelopes are identical, 1 when
they differ, and 2 when fewer than two were recorded.
"""

import json
import pathlib
import sys

ARTIFACTS = pathlib.Path(__file__).parent / "artifacts"


def main():
    files = sorted(ARTIFACTS.glob("envelope-*.json"))
    if len(files) < 2:
        print(f"need at least two recorded envelopes to compare, found {len(files)}")
        return 2

    envelopes = {
        f.stem.removeprefix("envelope-"): json.loads(f.read_text()) for f in files
    }

    labels = list(envelopes)
    keys = set().union(*(e.keys() for e in envelopes.values()))

    print("Observed message envelopes:")
    for label, env in envelopes.items():
        print(f"  {label}: {json.dumps(env, sort_keys=True)}")
    print()

    diffs = {
        k: {lbl: envelopes[lbl].get(k) for lbl in labels}
        for k in sorted(keys)
        if len({json.dumps(envelopes[lbl].get(k), sort_keys=True) for lbl in labels})
        > 1
    }

    if not diffs:
        print("PARITY: recorded envelopes are identical across implementations.")
        return 0

    print("DIVERGENCE: the implementations differ on these envelope fields:")
    for k, vals in diffs.items():
        print(f"  {k}: " + ", ".join(f"{lbl}={vals[lbl]!r}" for lbl in labels))
    return 1


if __name__ == "__main__":
    sys.exit(main())
