#!/usr/bin/env python3
"""Generate the alt_bn128 precompile vectors from published corpora.

Usage:
  scripts/gen-altbn128-vectors.py <geth-testdata-dir> <besu-native-csv-dir> \
                                  <eest-byzantium-fixture-dir> <output.txt>

WHY A TEXT RESOURCE RATHER THAN GENERATED SCALA. The same reasoning
gen-modexp-vectors.py records: the corpus runs to some 300 KB of hex, which is
data rather than code and does not need compiling on every build. A
line-oriented resource keeps the whole corpus, stays reviewable in a diff, and
needs no JSON parser on the Scala side.

FORMAT, one vector per line, four space-separated fields:
    <op> <0x-prefixed input> <expectation> <name>
`op` is `add`, `mul` or `pairing`. `expectation` is one of
    0x<hex>        the exact answer, stated by the corpus
    keccak:<hex>   the Keccak-256 of the answer, which is all the corpus states
    halt           the call fails, consuming everything
BOTH the input and a literal answer carry an `0x` prefix. Empty is a real input
here -- EIP-196 admits it for all three, and EIP-197 makes it the one input that
answers one without a pairing -- so a bare empty field would leave a line with
three fields on it, which a reader splitting on spaces would take as the name
having moved.

THE keccak FORM IS NOT A WEAKER ROW, it is a different corpus. The fixture
release drives each precompile through a caller contract that stores
`keccak256(returndata)`, so the digest is what that corpus states and the answer
itself is nowhere in it. A reader hashes its own answer and compares, which
checks the same thing the literal rows check without this script computing an
expected value -- nothing here derives an answer, every field is transcribed.

WHAT THE CLASSIFIER IS FOR. `halt` rows are grouped by which refusal they reach
-- a field element at or above the modulus, a point off the curve, a G2 point
off the q-order subgroup, a pairing input whose length is not a whole number of
pairs. That grouping is NAMING, never an assertion: the row asserts the call
fails, and the name says which rule the corpus's own author was aiming at. It is
computed here so that a row nobody can classify shows up as `unclassified` in
the name rather than passing silently as coverage of a rule it never reaches.

THREE CORPORA, all read for what they state and none for what it computes. The
paths are PARAMETERS: they are machine-local and this repository is public, so
no path to any of them may appear in a committed file.
"""
import json
import re
import sys
from pathlib import Path

P = 21888242871839275222246405745257275088696311157297823662689037894645226208583
Q = 21888242871839275222246405745257275088548364400416034343698204186575808495617

# --- Fp2 = Fp[i]/(i^2 + 1), written (real, imaginary). Only enough of it to
# --- classify a refusal; nothing here computes an answer.
def f2mul(x, y):
    return ((x[0] * y[0] - x[1] * y[1]) % P, (x[0] * y[1] + x[1] * y[0]) % P)


def f2add(x, y):
    return ((x[0] + y[0]) % P, (x[1] + y[1]) % P)


def f2sub(x, y):
    return ((x[0] - y[0]) % P, (x[1] - y[1]) % P)


def f2inv(x):
    norm = pow((x[0] * x[0] + x[1] * x[1]) % P, P - 2, P)
    return ((x[0] * norm) % P, (-x[1] * norm) % P)


B2 = f2mul((3, 0), f2inv((9, 1)))


def g2_double(point):
    x, y = point
    slope = f2mul(f2mul((3, 0), f2mul(x, x)), f2inv(f2mul((2, 0), y)))
    xr = f2sub(f2mul(slope, slope), f2mul((2, 0), x))
    return (xr, f2sub(f2mul(slope, f2sub(x, xr)), y))


def g2_add(left, right):
    if left is None:
        return right
    if right is None:
        return left
    if left[0] == right[0]:
        return g2_double(left) if left[1] == right[1] else None
    slope = f2mul(f2sub(right[1], left[1]), f2inv(f2sub(right[0], left[0])))
    xr = f2sub(f2sub(f2mul(slope, slope), left[0]), right[0])
    return (xr, f2sub(f2mul(slope, f2sub(left[0], xr)), left[1]))


def g2_multiply(point, scalar):
    accumulator, addend = None, point
    while scalar > 0:
        if scalar & 1:
            accumulator = g2_add(accumulator, addend)
        addend = g2_double(addend)
        scalar >>= 1
    return accumulator


def padded(raw, offset, width):
    window = raw[offset : offset + width]
    return window.ljust(width, b"\x00")


def word(raw, offset):
    return int.from_bytes(padded(raw, offset, 32), "big")


def classify_g1(raw, offset):
    x, y = word(raw, offset), word(raw, offset + 32)
    if x >= P or y >= P:
        return "field"
    if x == 0 and y == 0:
        return None
    if (y * y - x * x * x - 3) % P != 0:
        return "curve"
    return None


def classify_g2(raw, offset):
    coordinates = [word(raw, offset + 32 * i) for i in range(4)]
    if any(value >= P for value in coordinates):
        return "field"
    x = (coordinates[1], coordinates[0])
    y = (coordinates[3], coordinates[2])
    if x == (0, 0) and y == (0, 0):
        return None
    if f2mul(y, y) != f2add(f2mul(f2mul(x, x), x), B2):
        return "curve"
    if g2_multiply((x, y), Q) is not None:
        return "subgroup"
    return None


def refusal(op, raw):
    if op == "add":
        return classify_g1(raw, 0) or classify_g1(raw, 64) or "unclassified"
    if op == "mul":
        return classify_g1(raw, 0) or "unclassified"
    if len(raw) % 192 != 0:
        return "length"
    for start in range(0, len(raw), 192):
        found = classify_g1(raw, start) or classify_g2(raw, start + 64)
        if found is not None:
            return found
    return "unclassified"


def unprefixed(value):
    return (value[2:] if value.startswith("0x") else value).lower()


# --- geth: core/vm/testdata/precompiles/bn256{Add,ScalarMul,Pairing}.json.
# Every row states an input and the answer; none states a refusal.
GETH_FILES = (("add", "bn256Add.json"), ("mul", "bn256ScalarMul.json"), ("pairing", "bn256Pairing.json"))


def from_geth(root):
    lines = []
    for op, filename in GETH_FILES:
        for entry in json.loads((root / filename).read_text()):
            name = entry.get("Name", "unnamed").replace(" ", "_")
            lines.append(
                f"{op} 0x{unprefixed(entry['Input'])} 0x{unprefixed(entry['Expected'])} geth/{op}/{name}"
            )
    return lines


# --- besu-native: the gnark CSVs. A row whose `notes` column is non-empty is an
# --- error row and its `result` column is not asserted -- that is the test's own
# --- reading of the file, not an inference about it.
BESU_FILES = (("add", "eip196_g1_add.csv"), ("mul", "eip196_g1_mul.csv"), ("pairing", "eip196_pairing.csv"))


def from_besu(root):
    lines = []
    for op, filename in BESU_FILES:
        rows = (root / filename).read_text().splitlines()
        for index, row in enumerate(rows[1:]):
            fields = row.split(",", 3)
            if len(fields) < 4 or not fields[0].strip():
                continue
            raw = bytes.fromhex(unprefixed(fields[0].strip()))
            if fields[3].strip():
                lines.append(
                    f"{op} 0x{raw.hex()} halt besu/{op}/{refusal(op, raw)}-{index}"
                )
            else:
                lines.append(
                    f"{op} 0x{raw.hex()} 0x{unprefixed(fields[1].strip())} besu/{op}/{index}"
                )
    return lines


# --- the fixture release. Each case drives one precompile through a caller that
# --- stores the call's success flag, the returned length, and -- where the call
# --- succeeded -- the Keccak-256 of what came back.
EEST_FILES = (
    ("add", "eip196_ec_add_mul/ecadd/valid.json"),
    ("add", "eip196_ec_add_mul/ecadd/invalid.json"),
    ("mul", "eip196_ec_add_mul/ecmul/valid.json"),
    ("mul", "eip196_ec_add_mul/ecmul/invalid.json"),
    ("pairing", "eip197_ec_pairing/ecpairing/valid.json"),
    ("pairing", "eip197_ec_pairing/ecpairing/fail.json"),
    ("pairing", "eip197_ec_pairing/ecpairing/invalid.json"),
    ("pairing", "eip197_ec_pairing/ecpairing_fuzzed/positive.json"),
    ("pairing", "eip197_ec_pairing/ecpairing_fuzzed/negative.json"),
    ("pairing", "eip197_ec_pairing/ecpairing_fuzzed/invalid_g1_point.json"),
    ("pairing", "eip197_ec_pairing/ecpairing_fuzzed/invalid_g2_point.json"),
    ("pairing", "eip197_ec_pairing/ecpairing_fuzzed/invalid_g2_subgroup.json"),
)
CASE = re.compile(r"-([A-Za-z0-9_]+)-?[a-z]*\]$")


def from_eest(root):
    lines = []
    for op, relative in EEST_FILES:
        bucket = Path(relative).parent.name + "/" + Path(relative).stem
        for identifier, case in sorted(json.loads((root / relative).read_text()).items()):
            found = CASE.search(identifier)
            label = found.group(1) if found else "unnamed"
            data = bytes.fromhex(unprefixed(case["blocks"][0]["transactions"][0]["data"]))
            storage = {}
            for account in case["postState"].values():
                if account.get("storage"):
                    storage = account["storage"]
            succeeded = int(storage.get("0x00", "0x0"), 16) == 1
            if not succeeded:
                lines.append(f"{op} 0x{data.hex()} halt eest/{bucket}/{refusal(op, data)}-{label}")
                continue
            digest = storage.get("0x02")
            if digest is None:
                raise SystemExit(f"case succeeded and states no digest, so it would be dropped: {identifier}")
            lines.append(f"{op} 0x{data.hex()} keccak:{unprefixed(digest).zfill(64)} eest/{bucket}/{label}")
    return lines


HEADER = """\
# alt_bn128 vectors: <op> <0x-prefixed input> <expectation> <name>
# op is add, mul or pairing. The expectation is `0x<hex>` for an answer the
# corpus states outright, `keccak:<hex>` for one it states only as a digest, and
# `halt` for a call that fails. The input carries an 0x prefix because empty is
# a real input to all three.
#
# A `halt` row's name says which refusal the input reaches -- field, curve,
# subgroup, length. That is naming and not an assertion: the row asserts only
# that the call fails.
#
# geth/*   ethereum/go-ethereum-pow @ v1.10.26,
#          core/vm/testdata/precompiles/bn256{Add,ScalarMul,Pairing}.json, every
#          row, names and all. Each states an input and the answer; the corpus
#          carries no refusal, so nothing here is a `halt` row. Its `Gas` column
#          is not read: those figures are the LATER schedule's, and this fork's
#          are asserted from the two documents instead.
# besu/*   besu-eth/besu-native @ 9b050ae262, the gnark test resources
#          gnark/src/test/resources/.../eip196_{g1_add,g1_mul,pairing}.csv. A row
#          whose fourth column is non-empty is one that must fail, and its
#          `result` column is then not asserted -- which is that suite's own
#          reading of its file, in AltBN128*PrecompiledContractTest.
# eest/*   ethereum/execution-specs-fixtures, release tests@v20.0.1,
#          fixtures/blockchain_tests/for_byzantium/byzantium/eip19{6,7}_*. Each
#          case calls one precompile from a contract that stores the success
#          flag and the Keccak-256 of what came back, so a passing case states
#          its answer as a digest and a failing one states only that it failed.
#          This is the ONLY corpus of the three that reaches the G2 subgroup
#          rule, in ecpairing_fuzzed/invalid_g2_subgroup.
"""


# EIP-197's own generator of the second group, and that generator with one
# coefficient moved. The classifier below is used to NAME a refusal and to
# cross-check every answering row, so it is calibrated against a point it must
# admit and one it must reject before either use -- a subgroup test that always
# says "not in the subgroup" would name every on-curve refusal `subgroup` and
# still look like it was working.
GENERATOR = (
    11559732032986387107991004021392285783925812861821192530917403151452391805634,
    10857046999023057135944570762232829481370756359578518086990519993285655852781,
    4082367875863433681332203403145435568316851327593401208105741076214120093531,
    8495653923123431417604973247489272438418190587263600148770280649306958101930,
)


def calibrate():
    encoded = b"".join(value.to_bytes(32, "big") for value in GENERATOR)
    if classify_g2(encoded, 0) is not None:
        raise SystemExit("classifier rejects the generator of the second group")
    moved = list(GENERATOR)
    moved[0] = (moved[0] + 1) % P
    perturbed = b"".join(value.to_bytes(32, "big") for value in moved)
    if classify_g2(perturbed, 0) != "curve":
        raise SystemExit("classifier admits a point one step off the curve")


def main():
    if len(sys.argv) != 5:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    calibrate()
    geth, besu, eest, out_path = (Path(argument) for argument in sys.argv[1:])
    lines = from_geth(geth) + from_besu(besu) + from_eest(eest)

    # Every row a corpus says ANSWERS must be one the model above finds nothing
    # wrong with. A disagreement is either the model or the corpus being wrong
    # and neither is something to emit quietly.
    for line in lines:
        op, encoded, expectation, name = line.split()
        if expectation == "halt":
            continue
        found = refusal(op, bytes.fromhex(encoded[2:]))
        if found != "unclassified":
            raise SystemExit(f"corpus states an answer for a row the rules refuse: {name} ({found})")

    Path(out_path).write_text(HEADER + "\n".join(lines) + "\n")
    kinds = {"literal": 0, "keccak": 0, "halt": 0}
    for line in lines:
        expectation = line.split()[2]
        kinds["halt" if expectation == "halt" else "keccak" if expectation.startswith("keccak:") else "literal"] += 1
    refusals = {}
    for line in lines:
        if line.split()[2] == "halt":
            tail = line.split()[3].rsplit("/", 1)[1].rsplit("-", 1)[0]
            refusals[tail] = refusals.get(tail, 0) + 1
    print(f"wrote {out_path}: {len(lines)} vectors {kinds}")
    print(f"  refusals reached: {refusals}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
