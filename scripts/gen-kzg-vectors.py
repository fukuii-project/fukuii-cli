#!/usr/bin/env python3
"""Generate the KZG proof-verification vectors from the published corpus.

Usage:
  scripts/gen-kzg-vectors.py <verify-kzg-proof.json> <output.txt>

WHY A TEXT RESOURCE RATHER THAN GENERATED SCALA. The reasoning
gen-altbn128-vectors.py records, unchanged: the corpus is data rather than code
and does not need compiling on every build, a line-oriented resource stays
reviewable in a diff, and the Scala side needs no JSON parser.

FORMAT, one vector per line, six space-separated fields:
    <verdict> <commitment> <z> <y> <proof> <name>
`verdict` is one of
    holds      the proof verifies
    fails      the proof does not verify, over arguments that all name points
    refuse     the arguments do not name points at all, so nothing is verified
Every byte string carries an `0x` prefix, as the corpus writes it. The prefix is
kept so a reader splitting on spaces sees the same shape the sibling corpora
use, and because it is what marks a `refuse` row's argument as deliberately
empty rather than as a field that went missing.

AN ARGUMENT IS FIXED-WIDTH ONLY WHERE THE CORPUS SAYS IT NAMES A POINT. `holds`
and `fails` rows carry 48/32/32/48 and that is asserted. `refuse` rows do not
have to: a truncated commitment is one of the things this corpus refuses, so the
width is counted there rather than required. Those rows exercise the primitive
and NOT the precompile, whose own width rule turns them away before an argument
is read.

WHY THREE VERDICTS AND NOT TWO. The published corpus states its expectation as
`true`, `false` or `null`, and the third is a different outcome rather than a
missing one: `null` marks arguments the library declines -- a commitment or
proof that is not a compressed point of the right group, a field element at or
above the modulus. EIP-4844's precompile answers `false` and `null` identically,
but `org.fukuii.evm.Kzg` does not, so collapsing them here would delete the only
published statement of which is which.

NOTHING HERE DERIVES AN ANSWER. Every field is transcribed. The verdict is read
off the corpus's own `output`, never computed, so this script cannot vouch for a
vector and does not try to -- what it can get wrong is reading the WRONG FIELD,
which is what the census guard below is for.

THE CENSUS GUARD. A transcriber fails silently: rename a key upstream and this
writes well-formed rows of empty strings. So every field is checked for the
shape it must have, a `holds` or `fails` row's widths are required exactly, and
all three verdicts must be present -- a corpus of nothing but `holds` would pass
against a build that answers `true` unconditionally. A mismatch exits non-zero
before anything is written. That is this script's whole calibration: it has no
derivation to validate, only a read to prove it performed.

The guard has already earned itself. Written to require 48/32/32/48 on every
row, it rejected the corpus outright -- which is how the off-width `refuse` rows
above were found, rather than by them being silently reshaped into rows that
looked right.

THE PATH IS A PARAMETER. It is machine-local and this repository is public, so
no path to the corpus may appear in a committed file.
"""
import json
import sys
from pathlib import Path

# What each argument must occupy, in bytes. A commitment and a proof are
# compressed BLS12-381 G1 points; a point and a claimed value are scalar field
# elements.
WIDTHS = {"commitment": 48, "z": 32, "y": 32, "proof": 48}

VERDICTS = {True: "holds", False: "fails", None: "refuse"}


def main(argv):
    if len(argv) != 3:
        sys.exit(__doc__)
    source, out_path = Path(argv[1]), Path(argv[2])

    corpus = json.loads(source.read_text())
    if not isinstance(corpus, list) or not corpus:
        sys.exit("CALIBRATION FAILED: corpus is not a non-empty list")

    rows, census, offwidth = [], {"holds": 0, "fails": 0, "refuse": 0}, set()
    for entry in corpus:
        name = entry["name"]
        output = entry["output"]
        if output not in VERDICTS:
            sys.exit(f"CALIBRATION FAILED: {name} states output {output!r}")
        verdict = VERDICTS[output]
        fields = []
        for key, width in WIDTHS.items():
            value = entry["input"][key]
            if not value.startswith("0x"):
                sys.exit(f"CALIBRATION FAILED: {name} field {key} is {value!r}, which has no 0x prefix")
            body = value[2:]
            if len(body) % 2 or any(c not in "0123456789abcdefABCDEF" for c in body):
                sys.exit(f"CALIBRATION FAILED: {name} field {key} is {value!r}, which is not whole hex bytes")
            # A width is asserted only where the corpus says the arguments name
            # points. A `refuse` vector may carry ANY width and several do --
            # a truncated commitment is one of the things that corpus is
            # refusing, so demanding 48 bytes there would reject the very rows
            # the verdict exists for. Counted below instead, because that count
            # is what tells a reader of the precompile how many of these rows
            # its own width rule reaches first.
            if verdict != "refuse" and len(body) != 2 * width:
                sys.exit(
                    f"CALIBRATION FAILED: {name} is `{verdict}` and its {key} is "
                    f"{len(body) // 2} bytes rather than {width}"
                )
            if len(body) != 2 * width:
                offwidth.add(name)
            fields.append(value)
        census[verdict] += 1
        rows.append(" ".join([verdict, *fields, name]))

    # The corpus must discriminate in all three directions, or a reader of these
    # rows learns nothing from them: a file of nothing but `holds` would pass
    # against a build that answers `true` unconditionally.
    for verdict, count in census.items():
        if count == 0:
            sys.exit(f"CALIBRATION FAILED: no {verdict} vector, so the corpus cannot discriminate")

    header = [
        "# KZG proof-verification vectors: <verdict> <commitment> <z> <y> <proof> <name>",
        "# verdict is `holds` (the proof verifies), `fails` (it does not, over",
        "# arguments that all name points) or `refuse` (the arguments do not name",
        "# points, so nothing is verified). Every byte string carries an 0x prefix.",
        "#",
        "# EIP-4844's precompile answers `fails` and `refuse` identically. The",
        "# primitive does not, which is why the corpus's own three-way split is",
        "# kept rather than collapsed -- see scripts/gen-kzg-vectors.py.",
        "#",
        f"# {census['holds']} holds, {census['fails']} fails, {census['refuse']} refuse.",
        "#",
        f"# {len(offwidth)} of the refuse rows carry an argument that is not its",
        "# fixed width -- a truncated commitment or proof is one of the things this",
        "# corpus refuses. Those rows reach the PRIMITIVE only: laid end to end they",
        "# do not occupy 192 bytes, so the precompile refuses them on width before",
        "# any argument is read.",
        "#",
        "# Source: ethereum/execution-specs, tests/cancun/eip4844_blobs/",
        "# point_evaluation_vectors/go_kzg_4844_verify_kzg_proof.json -- every row,",
        "# names and all. Transcribed; no field is computed here.",
    ]
    out_path.write_text("\n".join(header + rows) + "\n")
    print(f"wrote {len(rows)} vectors to {out_path}: {census}")


if __name__ == "__main__":
    main(sys.argv)
