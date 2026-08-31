#!/usr/bin/env python3
"""Generate the BLAKE2 F precompile vectors from EIP-152's own Test Cases.

Usage: scripts/gen-blake2f-vectors.py <path-to-eip-152.md> <output.txt>

WHY THIS EXISTS. The vector file it writes is tracked, and a tracked corpus
whose generator is not tracked cannot be regenerated, re-pointed at a newer ref,
or audited against its source -- so the vectors would be nine hex strings that a
reader has to take on faith. `gen-p256-vectors.py` beside this one is the same
argument already applied once.

WHY A TEXT RESOURCE RATHER THAN GENERATED SCALA. One vector's input is 213 bytes
and its output 64; as source these would be literals nobody can diff usefully,
and the project declares no JSON library, which test data is not a good reason
to open. A line-oriented resource stays reviewable and needs no parser.

FORMAT, one vector per line: <input hex or `-`> <output hex or REFUSED> <name>
`-` is the empty input, which the document states as "(empty)" rather than as a
hex string. REFUSED covers both of the document's refusals -- a wrong width and
a wrong final-block byte -- because they are one observable to a caller: every
halt is exceptional, so the invocation keeps nothing either way. A generator
that distinguished them would be asserting a difference this precompile's
contract does not carry.

THE SOURCE PATH IS A PARAMETER. It is machine-local and this repository is
public, so no path to a corpus may appear in a committed file. Point it at
`EIPS/eip-152.md` in a clone of `ethereum/EIPs`, and record the commit you read
it at in whatever cites the result -- the document is Final, but a repository
without tags gives a SHA and nothing else.

CALIBRATION. This script refuses to write a file it cannot vouch for, because a
vector corpus is an instrument that only ever answers: a silently short or
mis-parsed corpus produces a test suite that passes over fewer cases than anyone
believes. So the counts below are asserted rather than reported.
"""
import re
import sys
from pathlib import Path

# What the document is expected to carry, so a parse that drifts fails loudly
# rather than emitting a shorter corpus that still looks like one.
EXPECTED_VECTORS = 9
EXPECTED_REFUSALS = 4
EXPECTED_ANSWERS = 5
PACKED_WIDTH = 213
STATE_WIDTH = 64

VECTOR = re.compile(r"^#### Test vector (\d+)\s*$")
HEX = re.compile(r"`([0-9a-fA-F]+)`")


def parse(text: str) -> list[tuple[str, str, str]]:
    """Every `#### Test vector N` block, as (input, output, name)."""
    lines = text.splitlines()
    starts = [i for i, line in enumerate(lines) if VECTOR.match(line)]
    if not starts:
        raise ValueError("no '#### Test vector N' heading found: wrong file, or the markup moved")

    out = []
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(lines)
        block = "\n".join(lines[start:end])
        number = VECTOR.match(lines[start]).group(1)

        head, _, tail = block.partition("* output:")
        if not tail:
            raise ValueError(f"vector {number}: no '* output:' line")

        # The empty input is prose, not a hex string, and is the one case where
        # finding no backticked run is correct rather than a parse failure.
        if "(empty)" in head:
            packed = "-"
        else:
            found = HEX.findall(head)
            if len(found) != 1:
                raise ValueError(f"vector {number}: expected one input hex run, found {len(found)}")
            packed = found[0].lower()
            if len(packed) % 2:
                raise ValueError(f"vector {number}: input is an odd number of hex digits")

        # A refusal is stated as prose beginning `error`; an answer is a
        # backticked run. Testing for the answer rather than for the word
        # "error" keeps a reworded message from turning a refusal into a
        # mis-parsed answer.
        answers = HEX.findall(tail)
        if answers:
            result = answers[0].lower()
            if len(result) != STATE_WIDTH * 2:
                raise ValueError(f"vector {number}: output is {len(result) // 2} bytes, expected {STATE_WIDTH}")
            if len(packed) != PACKED_WIDTH * 2:
                raise ValueError(f"vector {number}: answered a {len(packed) // 2}-byte input; only {PACKED_WIDTH} can be")
        elif "error" in tail:
            result = "REFUSED"
        else:
            raise ValueError(f"vector {number}: output is neither a hex run nor an error")

        out.append((packed, result, f"eip152-{number}"))
    return out


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    source, destination = Path(sys.argv[1]), Path(sys.argv[2])

    vectors = parse(source.read_text())
    refusals = sum(1 for _, result, _ in vectors if result == "REFUSED")
    answers = len(vectors) - refusals

    # Assert rather than report. See CALIBRATION above.
    if len(vectors) != EXPECTED_VECTORS:
        raise SystemExit(f"CALIBRATION FAILED: parsed {len(vectors)} vectors, expected {EXPECTED_VECTORS}")
    if refusals != EXPECTED_REFUSALS or answers != EXPECTED_ANSWERS:
        raise SystemExit(f"CALIBRATION FAILED: {answers} answered and {refusals} refused, expected {EXPECTED_ANSWERS}/{EXPECTED_REFUSALS}")

    destination.write_text("\n".join(f"{a} {b} {c}" for a, b, c in vectors) + "\n")
    print(f"wrote {destination}: {len(vectors)} vectors ({answers} answered, {refusals} refused)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
