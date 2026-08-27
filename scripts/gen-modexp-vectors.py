#!/usr/bin/env python3
"""Generate the MODEXP vectors from other implementations' published corpora.

Usage: scripts/gen-modexp-vectors.py <geth-modexp.json> <fixture-modexp.json> <output.txt>

WHY A TEXT RESOURCE RATHER THAN GENERATED SCALA. The same reasoning
gen-p256-vectors.py records: one of these inputs is 2 KB of hex and the set runs
to some 35 KB, which is data rather than code and does not need compiling on
every build. A line-oriented resource keeps the whole corpus, stays reviewable
in a diff, and needs no JSON parser on the Scala side.

FORMAT, one vector per line, four space-separated fields:
    <input hex> <gas|-> <0x-prefixed output|-> <name>
A `-` means the corpus that supplied the row does not state that half, never
that the row is exempt: the reader asserts each field it is given and counts
what it asserted, so a corpus that quietly stopped stating outputs shows up as a
count rather than as a pass.

The output carries an `0x` prefix and the input does not, which is not a style
slip. An answer of no bytes at all is a real answer here -- both declared
lengths empty produce one -- and a bare empty field would leave a line with
three fields on it, which a reader splitting on spaces would take as the name
having moved. `0x` keeps the field present. No input in either corpus is empty,
so the same hazard does not reach that field.

TWO CORPORA, AND THE FIRST IS ONE CORPUS AND NOT TWO. geth's
core/vm/testdata/precompiles/modexp.json and nethermind's
Nethermind.Evm.Test/PrecompileVectors/modexp.json were compared field by field
and are IDENTICAL -- same 17 names, inputs, outputs and gas figures, in the same
order. Reading both would be reading one corpus twice, so only geth's is taken
and nethermind's is recorded here as a copy of it rather than as agreement with
it. besu's MODEXPPrecompiledContractTest.java carries the same 17 again, plus
five rows of its own that state gas and no output.

WHAT IS TAKEN FROM BESU, AND WHY IT IS ONE ROW OF FIVE. Four of those five state
a Byzantium gas figure that is besu's own machine-integer ceiling rather than the
specification's number: three are Long.MAX_VALUE outright, and the fourth
(28928590731427686, for a base length of 2**42) is what besu's `square()` gives
once `clampedMultiply` has pinned x*x at Long.MAX_VALUE -- the specification's
figure for that input is 60446291086284574991820, some two thousand times
larger. Both refuse the call at any gas limit a transaction can state, so the
difference is not observable on a chain; it does make those four rows unusable
as expected values for an implementation that computes the exact figure. Only
besu's 1580-gas row is taken.

THE SECOND CORPUS is the execution-spec-tests fixture release, whose
eip198_modexp_precompile cases state an expected output in the case identifier
and no gas figure. Those rows carry `-` for gas. The one case whose identifier
says the call fails is skipped: it states no output for the precompile, only
that 2,000,000 gas does not buy the call.

Both corpus paths are PARAMETERS. They are machine-local and this repository is
public, so no path to either may appear in a committed file.
"""
import json
import re
import sys
from pathlib import Path

# The case identifier states the input's shape and the expected output; the
# input bytes themselves come from the transaction the case sends.
CASE = re.compile(r"ModExpInput_(?P<shape>.*?)-ModExpOutput_(?P<outcome>.*?)\]?$")
RETURNED = re.compile(r"returned_data_0x(?P<data>[0-9a-f]*)$")


def unprefixed(value: str) -> str:
    return (value[2:] if value.startswith("0x") else value).lower()


def from_client_corpus(src: Path) -> list[str]:
    lines = []
    for entry in json.loads(src.read_text()):
        name = entry.get("Name", "unnamed").replace(" ", "_")
        lines.append(
            f"{unprefixed(entry['Input'])} {entry['Gas']} 0x{unprefixed(entry['Expected'])} geth/{name}"
        )
    return lines


def from_fixture_corpus(src: Path) -> list[str]:
    lines = []
    for identifier, case in sorted(json.loads(src.read_text()).items()):
        found = CASE.search(identifier)
        if found is None:
            continue
        outcome = found.group("outcome")
        if "call_success_False" in outcome:
            continue
        returned = RETURNED.search(outcome)
        if returned is None:
            raise ValueError(f"case states no returned data: {identifier}")
        name = found.group("shape").replace(" ", "_")
        for data in case["transaction"]["data"]:
            lines.append(f"{unprefixed(data)} - 0x{returned.group('data')} eest/{name}")
    return lines


# besu's one Byzantium row whose gas figure is the specification's rather than
# its own ceiling: a base length of 227 declared by a header the input truncates,
# so both remaining lengths read as zero. besu states no output for it.
BESU_ROW = (
    "00000000000000000000000000000000000000000000000000000000000000e3"
    "00000000000000000000000000000000000000000000000000 1580 - besu/truncated-header-base-227"
)


HEADER = """\
# MODEXP vectors: <input hex> <gas|-> <0x-prefixed output|-> <name>
# A `-` is a half the corpus that supplied the row does not state. The output
# carries an 0x prefix so that an answer of no bytes still occupies its field.
#
# geth/*   ethereum/go-ethereum-pow @ v1.10.26,
#          core/vm/testdata/precompiles/modexp.json, all 17 rows, names and all.
#          NethermindEth/nethermind @ b92e2a4719 publishes a file identical to
#          it field for field, so this is one corpus read once rather than two
#          agreeing. besu-eth/besu @ fdf1247c6d carries the same 17 again.
# besu/*   besu-eth/besu @ fdf1247c6d,
#          evm/src/test/java/.../MODEXPPrecompiledContractTest.java. ONE of its
#          five extra rows is taken. The other four state a Byzantium gas figure
#          that is besu's own machine-integer ceiling rather than the
#          specification's number -- three are Long.MAX_VALUE, and the fourth
#          gives 28928590731427686 for a base declared 2**42 bytes wide, which
#          is what its square() yields once clampedMultiply has pinned x*x at
#          Long.MAX_VALUE, against 60446291086284574991820 exact.
# eest/*   ethereum/execution-specs-fixtures, release tests@v20.0.1,
#          fixtures/state_tests/for_byzantium/byzantium/eip198_modexp_precompile.
#          Twelve cases carry an expected output in the case identifier and no
#          gas figure. ELEVEN are taken: the twelfth states that 2,000,000 gas
#          does not buy the call, which is a claim about the charge and not
#          about what the precompile answers.
"""


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    client, fixtures, out_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])

    lines = from_client_corpus(client) + [BESU_ROW] + from_fixture_corpus(fixtures)

    priced = sum(1 for line in lines if line.split()[1] != "-")
    answered = sum(1 for line in lines if line.split()[2] != "-")
    Path(out_path).write_text(HEADER + "\n".join(lines) + "\n")
    print(f"wrote {out_path}: {len(lines)} vectors ({priced} state gas, {answered} state an output)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
