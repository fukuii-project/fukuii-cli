#!/usr/bin/env python3
"""Generate the secp256r1 verification vectors from besu's P256VERIFY corpus.

Usage: scripts/gen-p256-vectors.py <besu-p256verify_test_vectors.json> <output.txt>

WHY A TEXT RESOURCE RATHER THAN GENERATED SCALA. The RLP table is generated as
Scala because it is small. This corpus is 778 vectors of 160 input bytes; as
source it would be a ~260 KB file for the compiler to chew on every build, for
data that never needs to be code. A line-oriented resource keeps the whole
corpus, stays reviewable in a diff, and needs no JSON parser -- which matters,
because this project declares no JSON library and test data is not a good
reason to open that gated decision.

FORMAT, one vector per line: <160-byte input hex> <0|1> <name>
The input is msgHash(32) || r(32) || s(32) || x(32) || y(32), which is the
precompile's calling convention; the last field is besu's own name for the case,
kept so a failure names the Wycheproof case it came from rather than an index.

The corpus path is a PARAMETER. It is machine-local and this repository is
public, so no path to it may appear in a committed file.
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    src, out_path = Path(sys.argv[1]), Path(sys.argv[2])

    vectors = json.loads(src.read_text())
    lines, valid, invalid = [], 0, 0

    for entry in vectors:
        raw = entry["Input"]
        raw = raw[2:] if raw.startswith("0x") else raw
        if len(raw) != 320:
            raise ValueError(f"expected 160 input bytes, got {len(raw) // 2}")

        # besu encodes success as a 32-byte word of 1 and failure as no output
        # at all. Anything else would be a format change rather than a new case,
        # so it is rejected here instead of being coerced into a boolean.
        expected = entry["Expected"]
        expected = expected[2:] if expected.startswith("0x") else expected
        if expected == "":
            ok, invalid = 0, invalid + 1
        elif expected == "00" * 31 + "01":
            ok, valid = 1, valid + 1
        else:
            raise ValueError(f"unrecognised Expected value: {expected!r}")

        name = entry.get("Name", "unnamed").replace(" ", "_")
        lines.append(f"{raw.lower()} {ok} {name}")

    out_path.write_text("\n".join(lines) + "\n")
    print(f"wrote {out_path}: {len(lines)} vectors ({valid} valid, {invalid} invalid)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
