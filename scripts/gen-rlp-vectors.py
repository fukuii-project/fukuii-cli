#!/usr/bin/env python3
"""Generate the RLP vector table from the ethereum/tests fixtures.

Usage: scripts/gen-rlp-vectors.py <RLPTests-dir> <output.scala>

WHY GENERATED RATHER THAN HAND-WRITTEN. The vectors must come from the corpus,
not from a reading of our own implementation -- a table typed out by whoever
wrote the codec agrees with that codec's misunderstandings. Generating it makes
the provenance mechanical and the regeneration reproducible.

WHY NOT READ THE JSON AT TEST TIME. This project declares no JSON library, and
adding one is a dependency decision that is gated and operator-approved. Test
data is not a good reason to open that. The generated table is committed, so
the vectors are also visible in review, which a runtime parse would not be.

WHAT THIS SCRIPT DECIDES, AND WHAT IT MUST NOT. The fixtures give `in` as a JSON
string, integer, list, or "#"-prefixed big integer. Turning an integer into
bytes applies the Yellow Paper's scalar rule -- RLP(i) is RLP(BE(i)), minimal
big-endian -- so that rule lives here. It is self-checking: the fixture's own
`out` is the expected encoding, so a wrong BE would fail the generated test
rather than silently pass.

The corpus directory is a PARAMETER. It is machine-local and this repository is
public, so no path to it may appear in a committed file.
"""
import json
import sys
from pathlib import Path


def be(n: int) -> bytes:
    """Minimal-length big-endian. Zero is the empty byte array, per the scalar rule."""
    if n < 0:
        raise ValueError(f"negative scalar: {n}")
    return n.to_bytes((n.bit_length() + 7) // 8, "big")


def to_bytes(s: str) -> bytes:
    """A fixture string denotes a byte sequence, one byte per character."""
    for ch in s:
        if ord(ch) > 0xFF:
            raise ValueError(f"character above 0xFF in fixture string: {ch!r}")
    return bytes(ord(ch) for ch in s)


def expr(value) -> str:
    if isinstance(value, list):
        return "seq(" + ", ".join(expr(v) for v in value) + ")"
    if isinstance(value, bool):
        raise ValueError("unexpected boolean in fixture")
    if isinstance(value, int):
        return f'b("{be(value).hex()}")'
    if isinstance(value, str):
        if value.startswith("#"):
            return f'b("{be(int(value[1:])).hex()}")'
        return f'b("{to_bytes(value).hex()}")'
    raise ValueError(f"unexpected fixture value type: {type(value)}")


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    src, out_path = Path(sys.argv[1]), Path(sys.argv[2])

    valid = json.loads((src / "rlptest.json").read_text())
    invalid = json.loads((src / "invalidRLPTest.json").read_text())

    valid_rows, invalid_rows = [], []
    for name, case in valid.items():
        want = case["out"]
        want = want[2:] if want.startswith("0x") else want
        valid_rows.append(f'    ("{name}", {expr(case["in"])}, "{want.lower()}")')
    for name, case in invalid.items():
        got = case["out"]
        got = got[2:] if got.startswith("0x") else got
        invalid_rows.append(f'    ("{name}", "{got.lower()}")')

    out_path.write_text(
        "package org.fukuii.rlp\n"
        "\n"
        "import org.fukuii.bytes.Hex\n"
        "import org.scalatest.prop.TableDrivenPropertyChecks.Table\n"
        "\n"
        "/** GENERATED — do not edit by hand.\n"
        "  *\n"
        "  * Regenerate with `scripts/gen-rlp-vectors.py <RLPTests-dir> <this-file>`\n"
        "  * against the `RLPTests` directory of the ethereum/tests corpus. That\n"
        "  * corpus is machine-local, so the path is a parameter rather than a\n"
        "  * constant; the ref these vectors were taken at is recorded in the spec\n"
        "  * that consumes them.\n"
        "  *\n"
        "  * A fixture's `in` is its decoded value and its `out` is the one canonical\n"
        "  * encoding of it. The invalid table carries encodings only: each must be\n"
        "  * REJECTED, and several are well-formed items in a non-canonical spelling\n"
        "  * rather than structurally broken bytes.\n"
        "  *\n"
        "  * ==Not formatted, and that follows from being generated==\n"
        "  *\n"
        "  * The formatter wraps a long encoding across five lines. Two costs, and the\n"
        "  * second is the one that decides it: a vector stops being one greppable line\n"
        "  * to compare against the corpus it came from, and the file stops matching what\n"
        "  * the generator emits — so the next regeneration would produce a diff made\n"
        "  * entirely of layout. A generated file is formatted by its generator or not at\n"
        "  * all.\n"
        "  */\n"
        # The marker below must be EMITTED, not added to the output by hand. A
        # generated file cannot carry an edit the generator does not know about:
        # the next run drops it silently, the formatter re-wraps every vector,
        # and nothing says why.
        "// format: off\n"
        "object RlpVectors:\n"
        "\n"
        "  private def b(hex: String): RlpItem =\n"
        "    RlpItem.Bytes(Hex.decode(hex).toOption.get)\n"
        "\n"
        "  private def seq(items: RlpItem*): RlpItem = RlpItem.Sequence(items.toVector)\n"
        "\n"
        "  val valid = Table(\n"
        '    ("name", "item", "encodedHex"),\n'
        + ",\n".join(valid_rows)
        + "\n  )\n"
        "\n"
        "  val invalid = Table(\n"
        '    ("name", "encodedHex"),\n'
        + ",\n".join(invalid_rows)
        + "\n  )\n"
    )
    print(f"wrote {out_path}: {len(valid_rows)} valid, {len(invalid_rows)} invalid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
