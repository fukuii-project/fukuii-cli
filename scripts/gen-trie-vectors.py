#!/usr/bin/env python3
"""Generate the Merkle-Patricia trie vector table.

Usage: scripts/gen-trie-vectors.py <TrieTests-dir> <output.txt>

THE CORPUS IS A PARAMETER, and the ref it came from is read from that
directory's own git metadata and named in the output file's header rather than
here -- this file is committed and public, so no path to a local clone may
appear in it.

TWO SOURCES OF ROW, AND THE LABEL SAYS WHICH.

  corpus-*   ethereum/tests TrieTests. The published `root` is what the row
             carries. THE GATE: the executable specification's own trie is
             driven over the same decoded entries and required to reproduce
             that published root before the row is emitted. A row whose two
             sources disagree is reported and NOT written.

  authored-* THE ONE CASE NO PUBLISHED VECTOR REACHES. A node whose RLP
             encoding is under 32 bytes is embedded in its parent rather than
             referenced by hash; the root has no parent, so the root of such a
             trie is the keccak of its own encoding regardless of width. Every
             published vector's root node is 32 bytes or more -- the margin is
             measured and printed by this script, not assumed -- so an
             implementation that gets the exception wrong passes the whole
             published corpus.

             These rows have no published root by construction, so the gate is
             TWO INDEPENDENT DERIVATIONS instead: the executable specification's
             `root()`, and a direct construction by the minimal RLP encoder
             below, which shares no code with either. They must agree.

THE DECODING RULE, which the corpus documents only in part. Its own
docs/test_sample/trie_tests.rst defines `hexEncoded`, and is silent on the `0x`
prefixes that appear in files NOT carrying that flag. The per-string rule --
`0x` prefix means hex, anything else means UTF-8 octets -- is besu's, read from
its own TrieTests runner, and it subsumes the documented flag: every string in
the `hexEncoded` file already carries the prefix. A `null` value is a DELETE.

WHAT THE CORPUS CANNOT REACH, beyond the root-node exception above: 25 of its
26 cases carry a `root`. The 26th, trietestnextprev.json's `basic`, carries
`in` and `tests` and no root at all -- it specifies next/prev iteration rather
than a commitment, so it is not a root vector and is not emitted. That is the
whole of the difference between "6 files" and "25 vectors".
"""
import json
import pathlib
import subprocess
import sys

if len(sys.argv) != 3:
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    raise SystemExit(2)

TRIE_TESTS = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])

try:
    import ethereum.merkle_patricia_trie as mpt
except ImportError:  # pragma: no cover - environment guard
    print(
        "the executable specification is not importable; put its src/ on "
        "PYTHONPATH (and redirect __pycache__ so the corpus is not mutated)",
        file=sys.stderr,
    )
    raise SystemExit(2)


def corpus_ref(path: pathlib.Path) -> str:
    try:
        sha = subprocess.run(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        date = subprocess.run(
            ["git", "-C", str(path), "log", "-1", "--format=%ad", "--date=short"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        return f"{sha} ({date})"
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "UNKNOWN -- not a git checkout, so this table's provenance is unverified"


def spec_ref(module) -> str:
    """The revision of the executable specification that GATED this table.

    Recorded because naming the gate without a ref is not a citation: the gate
    is what makes a row evidence rather than transcription, and the corpus
    release a table draws its DATA from is a different ref from the checkout
    whose code did the gating. Those two can legitimately differ, and a header
    naming only the first reads as though one ref covered both.
    """
    try:
        here = pathlib.Path(module.__file__).resolve()
    except (AttributeError, TypeError):
        return "UNKNOWN -- the gate module has no file, so this table's gate is unattributable"
    for parent in here.parents:
        if (parent / ".git").exists():
            return corpus_ref(parent)
    return "UNKNOWN -- the gate module is not in a git checkout, so its revision is unrecorded"


# ---------------------------------------------------------------------------
# A minimal RLP encoder and keccak, independent of both the specification and
# the implementation under test. Used only to derive the authored rows a second
# time, so their expected root rests on two derivations rather than one.
# ---------------------------------------------------------------------------
def enc_len(n: int, offset: int) -> bytes:
    if n < 56:
        return bytes([offset + n])
    lb = (n.bit_length() + 7) // 8
    return bytes([offset + 55 + lb]) + n.to_bytes(lb, "big")


def enc_bytes(x: bytes) -> bytes:
    if len(x) == 1 and x[0] <= 0x7F:
        return x
    return enc_len(len(x), 0x80) + x


def enc_list(items) -> bytes:
    body = b"".join(items)
    return enc_len(len(body), 0xC0) + body


def nibbles(key: bytes):
    out = []
    for b in key:
        out.append(b >> 4)
        out.append(b & 0x0F)
    return out


def compact(path, is_leaf: bool) -> bytes:
    flag = 2 if is_leaf else 0
    if len(path) % 2:
        head = [16 * (flag + 1) + path[0]]
        rest = path[1:]
    else:
        head = [16 * flag]
        rest = path
    return bytes(head) + bytes(16 * rest[i] + rest[i + 1] for i in range(0, len(rest), 2))


def independent_patricialize(obj, level: int):
    """The trie's definition, written from it rather than shared with the
    specification. Returns the root node's RLP encoding, or None when empty.

    This exists so an authored row -- which has no published root to be gated
    against -- rests on two derivations rather than on one implementation's
    say-so. It is deliberately the naive form: no node store, no incremental
    maintenance, structure as a pure function of the key set.
    """
    if not obj:
        return None
    if len(obj) == 1:
        path, value = next(iter(obj.items()))
        return enc_list([enc_bytes(compact(path[level:], True)), enc_bytes(value)])
    any_path = next(iter(obj))
    prefix = len(any_path) - level
    for path in obj:
        shared = 0
        while (
            shared < prefix
            and level + shared < len(path)
            and path[level + shared] == any_path[level + shared]
        ):
            shared += 1
        prefix = min(prefix, shared)
    if prefix > 0:
        child = independent_patricialize(obj, level + prefix)
        return enc_list(
            [enc_bytes(compact(any_path[level:level + prefix], False)), cap_ref(child)]
        )
    terminating = b""
    buckets = {i: {} for i in range(16)}
    for path, value in obj.items():
        if len(path) == level:
            terminating = value
        else:
            buckets[path[level]][path] = value
    children = [cap_ref(independent_patricialize(buckets[i], level + 1)) for i in range(16)]
    return enc_list(children + [enc_bytes(terminating)])


def cap_ref(encoding) -> bytes:
    """How a parent names a child: embedded when under 32 bytes, else the
    keccak of the encoding as an RLP string."""
    import ethereum.crypto.hash as chash

    if encoding is None:
        return enc_bytes(b"")
    if len(encoding) < 32:
        return encoding
    return enc_bytes(bytes(chash.keccak256(encoding)))


def independent_root(entries) -> tuple:
    """(root, root-node width) for an unsecured trie, by the derivation above."""
    import ethereum.crypto.hash as chash

    obj = {}
    for key, value in entries:
        if value:
            obj[tuple(nibbles(key))] = value
        else:
            obj.pop(tuple(nibbles(key)), None)
    encoding = independent_patricialize(obj, 0)
    if encoding is None:
        return bytes(chash.keccak256(enc_bytes(b""))), 0
    return bytes(chash.keccak256(encoding)), len(encoding)


# ---------------------------------------------------------------------------
# Corpus decoding
# ---------------------------------------------------------------------------
def decode(s):
    """besu's per-string rule: `0x` means hex, anything else means UTF-8."""
    if s is None:
        return None
    if s.startswith("0x"):
        body = s[2:]
        if len(body) % 2:
            raise ValueError(f"odd-length hex in corpus: {s!r}")
        return bytes.fromhex(body)
    return s.encode("utf-8")


def entries_of(case):
    """(key, value-or-None) in file order, plus whether order is meaningful."""
    raw = case["in"]
    if isinstance(raw, dict):
        return [(decode(k), decode(v)) for k, v in raw.items()], "set"
    return [(decode(p[0]), decode(p[1])) for p in raw], "sequence"


def spec_root(entries, secured: bool) -> bytes:
    trie = mpt.Trie(secured=secured, default=b"")
    for key, value in entries:
        mpt.trie_set(trie, key, b"" if value is None else value)
    return mpt.root(trie)


def spec_root_node_width(entries, secured: bool) -> int:
    """Width of the root node's OWN RLP encoding, which is what the inline rule
    at the root turns on. Zero entries has no root node, reported as 0.

    The `unencoded` construction below is `encode_internal_node`'s, repeated
    rather than called because that function returns the CAPPED form -- the
    embedded tuple under 32 bytes and a digest at or over it -- and the width
    being measured is what that cap decides on. `encode_node` is not the
    instrument either: it RLP-encodes the node dataclass, whose key field is
    the raw NIBBLE LIST rather than its compact form, so it reports a width
    roughly half a key wider than the node that is actually hashed.
    """
    from ethereum_rlp import rlp
    from ethereum_types.numeric import Uint

    trie = mpt.Trie(secured=secured, default=b"")
    for key, value in entries:
        mpt.trie_set(trie, key, b"" if value is None else value)
    obj = {}
    for k, v in trie._data.items():
        if v != b"":
            obj[mpt.bytes_to_nibble_list(k)] = v
    if not obj:
        return 0
    node = mpt.patricialize(obj, Uint(0))
    if isinstance(node, mpt.LeafNode):
        unencoded = (mpt.nibble_list_to_compact(node.rest_of_key, True), node.value)
    elif isinstance(node, mpt.ExtensionNode):
        unencoded = (mpt.nibble_list_to_compact(node.key_segment, False), node.subnode)
    elif isinstance(node, mpt.BranchNode):
        unencoded = list(node.subnodes) + [node.value]
    else:
        raise AssertionError(f"unexpected root node type {type(node)}")
    return len(rlp.encode(unencoded))


def token(b) -> str:
    if b is None:
        return "-"
    return b.hex() if b else "@"


rows = []
mismatches = []
widths = []
calibration_failures = []
calibrated = 0

for path in sorted(TRIE_TESTS.glob("*.json")):
    secured = "secure" in path.name.lower()
    data = json.loads(path.read_text())
    for name, case in data.items():
        if "root" not in case:
            continue
        entries, order = entries_of(case)
        published = decode(case["root"])
        computed = spec_root(entries, secured)
        label = f"corpus-{path.stem}-{name}"
        if computed != published:
            mismatches.append((label, published.hex(), computed.hex()))
            continue
        # Calibration: the independent derivation below authorizes the authored
        # rows, which have no published root. Run it against every published
        # root it can reach, so that it is a checked instrument rather than
        # unvalidated code vouching for vectors nothing else can check.
        spec_width = spec_root_node_width(entries, secured)
        if not secured:
            by_hand, hand_width = independent_root(entries)
            if by_hand != published:
                calibration_failures.append((label, published.hex(), by_hand.hex()))
            elif hand_width != spec_width:
                calibration_failures.append(
                    (label + " [width]", str(spec_width), str(hand_width))
                )
            else:
                calibrated += 1
        widths.append((label, spec_width))
        rows.append((label, "secured" if secured else "unsecured", order, entries, published))

# ---------------------------------------------------------------------------
# Authored rows: root nodes under the 32-byte inline limit.
# ---------------------------------------------------------------------------
# The 31/32 pair is the discriminating one: 31 is the widest root node the
# inline rule still embeds, 32 the narrowest it does not. An implementation
# that applies the child rule at the root splits them.
AUTHORED = [
    ("authored-leaf-root-5", [(bytes.fromhex("01"), bytes.fromhex("02"))]),
    ("authored-leaf-root-oddkey-6", [(bytes.fromhex("0102"), bytes.fromhex("03"))]),
    ("authored-leaf-root-31", [(bytes.fromhex("01"), bytes(26))]),
    ("authored-leaf-root-32", [(bytes.fromhex("01"), bytes(27))]),
    ("authored-leaf-root-33", [(bytes.fromhex("01"), bytes(28))]),
    (
        "authored-branch-root-22",
        [(bytes.fromhex("00"), bytes.fromhex("01")), (bytes.fromhex("10"), bytes.fromhex("02"))],
    ),
    (
        "authored-extension-root-24",
        [(bytes.fromhex("00"), bytes.fromhex("01")), (bytes.fromhex("01"), bytes.fromhex("02"))],
    ),
]

authored_rows = []
for label, entries in AUTHORED:
    by_spec = spec_root(entries, False)
    by_hand, width = independent_root(entries)
    if by_spec != by_hand:
        mismatches.append((label, by_hand.hex(), by_spec.hex()))
        continue
    authored_rows.append((label, "unsecured", "set", entries, by_spec, width))

if mismatches:
    print("DISAGREEMENT -- rows NOT emitted:", file=sys.stderr)
    for label, expected, got in mismatches:
        print(f"  {label}: expected {expected} got {got}", file=sys.stderr)

if calibration_failures:
    print("CALIBRATION FAILED -- the independent derivation is wrong:", file=sys.stderr)
    for label, expected, got in calibration_failures:
        print(f"  {label}: published {expected} independent {got}", file=sys.stderr)
    raise SystemExit(1)

# A trie that ends empty has NO root node, so its zero width is not a narrow
# root node and must not be reported as one -- excluding it is what keeps the
# margin claim below honest rather than trivially satisfied.
nonempty = [(label, w) for label, w in widths if w > 0]
empties = len(widths) - len(nonempty)
smallest = min(w for _, w in nonempty) if nonempty else 0
smallest_label = min(nonempty, key=lambda p: p[1])[0] if nonempty else "none"

lines = [
    "# Trie vectors: <label> <securing> <order> <entry-count> <root> <k>:<v> ...",
    "#",
    "# All hex is without 0x. A value token of `-` is a DELETE (the corpus's",
    "# JSON null); `@` is a genuinely empty byte string, which this trie treats",
    "# as removal too. `order` is `set` when the corpus gave `in` as a JSON",
    "# object -- the root must then not depend on insertion order, which is what",
    "# the trieanyorder files exist to pin -- and `sequence` when it gave a list,",
    "# where deletes make the order load-bearing.",
    "#",
    "# TWO SOURCES, AND THE LABEL SAYS WHICH.",
    "#",
    f"#   corpus-*   ethereum/tests @ {corpus_ref(TRIE_TESTS)}, TrieTests/.",
    "#              The root is the PUBLISHED one. Every row was gated before",
    "#              being written: the executable specification's own trie was",
    "#              driven over the same decoded entries and required to",
    "#              reproduce that published root.",
    f"#              gate revision: ethereum/execution-specs @ {spec_ref(mpt)}",
    "#              The corpus ref above and this one are separate refs and",
    "#              need not match: one supplies the data, the other the code",
    "#              that gated it.",
    "#",
    "#   authored-*  NO PUBLISHED VECTOR REACHES THIS CASE. A node whose RLP",
    "#              encoding is under 32 bytes is embedded in its parent; the",
    "#              root has no parent, so the root of such a trie is the keccak",
    "#              of its own encoding regardless of width. MEASURED over this",
    f"#              corpus, not assumed: the smallest published root node is",
    f"#              {smallest} bytes ({smallest_label}), so every published root",
    "#              is already at or over the limit and NOT ONE of them exercises",
    "#              the exception. An implementation that gets it wrong passes the",
    f"#              whole published corpus. ({empties} further cases end EMPTY and so",
    "#              have no root node at all; they are excluded from that minimum",
    "#              rather than counted as a zero-width one.)",
    "#",
    "#              These have no published root by construction, so the gate is",
    "#              TWO INDEPENDENT DERIVATIONS: the executable specification's",
    "#              root(), and a naive patricialize written from the definition,",
    "#              sharing no code with it. Both must agree.",
    "#",
    "#              THAT SECOND DERIVATION IS ITSELF CALIBRATED, or it would be",
    "#              unvalidated code vouching for the one set of vectors nothing",
    "#              else can check: it is run against every unsecured published",
    f"#              root above and reproduces all {calibrated} of them.",
    "#",
    "# THE DECODING RULE the corpus documents only in part: its own",
    "# docs/test_sample/trie_tests.rst defines `hexEncoded` and is silent on the",
    "# `0x` prefixes appearing in files without that flag. The per-string rule",
    "# used here -- `0x` means hex, anything else means UTF-8 octets -- is",
    "# besu's, from its own TrieTests runner, and subsumes the documented flag.",
    "#",
    "# NOT EVERY TrieTests CASE IS HERE, and the gap is declared: 25 of the 26",
    "# carry a `root`. trietestnextprev.json's `basic` carries `in` and `tests`",
    "# and no root -- it specifies next/prev iteration, not a commitment.",
]
if mismatches:
    lines.append("#")
    lines.append(f"# {len(mismatches)} ROW(S) WERE WITHHELD ON A SOURCE DISAGREEMENT.")

for label, securing, order, entries, root in rows:
    pairs = " ".join(f"{token(k)}:{token(v)}" for k, v in entries)
    lines.append(f"{label} {securing} {order} {len(entries)} {root.hex()} {pairs}")

for label, securing, order, entries, root, width in authored_rows:
    pairs = " ".join(f"{token(k)}:{token(v)}" for k, v in entries)
    lines.append(f"{label} {securing} {order} {len(entries)} {root.hex()} {pairs}")

OUT.write_text("\n".join(lines) + "\n")
print(
    f"wrote {len(rows)} corpus + {len(authored_rows)} authored rows -> {OUT}",
    file=sys.stderr,
)
print(f"smallest published root node: {smallest} bytes ({smallest_label})", file=sys.stderr)
for label, _, _, _, _, width in authored_rows:
    print(f"  authored root node width: {label} = {width} bytes", file=sys.stderr)
if mismatches:
    raise SystemExit(1)
