#!/usr/bin/env python3
"""Generate the account vector table.

Usage: scripts/gen-account-vectors.py <corpus-dir> [<corpus-dir> ...] <output.txt>

THE CORPUS IS A PARAMETER, and each one's ref is read from its own git metadata
and named in the output file's header -- this file is committed and public, so
no path to a local clone may appear in it.

MORE THAN ONE CORPUS DIRECTORY IS ACCEPTED, and that is not generality for its
own sake. The tracked table this script was written to reproduce draws rows
from two different published corpora, which is discoverable only by searching
both; a single-corpus generator cannot express what that table is. Every corpus
given is named in the header with the number of rows it supplied.

WHAT A ROW IS. Accounts are bucketed by (kind, byte-length of the minimal
nonce, byte-length of the minimal balance) and ONE is taken per bucket, where
kind is externally-owned (empty code) or contract (any other code). The
byte-length axes are what make the table a scalar-boundary sweep rather than a
sample: RLP encodes a quantity in its minimal form, so each byte-length is a
different encoding shape, and the table walks them.

ONLY ACCOUNTS WHOSE STORAGE IS EMPTY ARE TAKEN, because a row records a storage
root and any other root would have to be computed from the account's storage
trie. An account whose storage entries are all zero counts as empty, since a
zero slot is absent from the trie and such an account's root is the empty root.

THE PICK RULE IS DETERMINISTIC AND IS THE POINT. Within a bucket, candidates
are ordered by (corpus, relative path, case name, address) and the first is
taken. That ordering is a property of the published bytes rather than of the
filesystem, so two runs on two machines select the same account. A first-wins
rule over an undefined walk order would produce a table that regenerates
differently every time it is moved, which is the defect this script exists to
remove.

THE GATE: every row's octets come from the executable specification's own
`encode_account`, and are required to equal the output of the minimal RLP
encoder below before the row is written. That encoder shares no code with the
specification or with the implementation under test.
"""
import collections
import json
import pathlib
import subprocess
import sys

if len(sys.argv) < 3:
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    raise SystemExit(2)

CORPORA = [pathlib.Path(a) for a in sys.argv[1:-1]]
OUT = pathlib.Path(sys.argv[-1])

try:
    from ethereum.merkle_patricia_trie import encode_account
    from ethereum.state import Account
    import ethereum.crypto.hash as chash
    from ethereum_types.numeric import U256, Uint
except ImportError:  # pragma: no cover - environment guard
    print(
        "the executable specification is not importable; put its src/ on "
        "PYTHONPATH (and redirect __pycache__ so the corpus is not mutated)",
        file=sys.stderr,
    )
    raise SystemExit(2)

MAX_NONCE = 2**64 - 1
MAX_BALANCE = 2**256 - 1


# ---------------------------------------------------------------------------
# A minimal RLP encoder, independent of the specification and of the
# implementation under test, used to gate every row before it is emitted.
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


def minimal(n: int) -> bytes:
    """The Yellow Paper's scalar form: no leading zero byte, empty for zero."""
    return b"" if n == 0 else n.to_bytes((n.bit_length() + 7) // 8, "big")


def independent_account_rlp(nonce: int, balance: int, root: bytes, code_hash: bytes) -> bytes:
    return enc_list(
        [enc_bytes(minimal(nonce)), enc_bytes(minimal(balance)), enc_bytes(root), enc_bytes(code_hash)]
    )


# Derived from first principles rather than recalled: the empty trie's root is
# the digest of the RLP of the empty byte string, and the empty code hash is
# the digest of no bytes at all.
EMPTY_ROOT = bytes(chash.keccak256(enc_bytes(b"")))
EMPTY_CODE_HASH = bytes(chash.keccak256(b""))


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


def unhex(s):
    if s is None:
        return b""
    s = s[2:] if s.startswith("0x") else s
    if len(s) % 2:
        s = "0" + s
    return bytes.fromhex(s)


def as_int(s) -> int:
    if s in (None, "", "0x"):
        return 0
    return int(s, 16)


def blen(n: int) -> int:
    return (n.bit_length() + 7) // 8


# ---------------------------------------------------------------------------
# Harvest
# ---------------------------------------------------------------------------
candidates = collections.defaultdict(list)
scanned = 0
skipped_storage = 0
skipped_range = 0

for corpus in CORPORA:
    tag = corpus.name
    for path in sorted(corpus.glob("**/*.json")):
        try:
            data = json.loads(path.read_text())
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        rel = path.relative_to(corpus).as_posix()
        for name, case in sorted(data.items()):
            if not isinstance(case, dict):
                continue
            pre = case.get("pre")
            if not isinstance(pre, dict):
                continue
            for addr, acct in sorted(pre.items()):
                if not isinstance(acct, dict):
                    continue
                storage = acct.get("storage", {}) or {}
                if not isinstance(storage, dict):
                    continue
                try:
                    nonce = as_int(acct.get("nonce", "0x0"))
                    balance = as_int(acct.get("balance", "0x0"))
                    code = unhex(acct.get("code", "0x"))
                    values = [as_int(v) for v in storage.values()]
                except (ValueError, TypeError):
                    continue
                scanned += 1
                if any(v != 0 for v in values):
                    skipped_storage += 1
                    continue
                if nonce > MAX_NONCE or balance > MAX_BALANCE:
                    skipped_range += 1
                    continue
                code_hash = bytes(chash.keccak256(code))
                kind = "externally-owned" if code_hash == EMPTY_CODE_HASH else "contract"
                key = (kind, blen(nonce), blen(balance))
                candidates[key].append((tag, rel, name, addr, nonce, balance, code_hash))

picked = {}
for key, options in candidates.items():
    picked[key] = min(options, key=lambda c: (c[0], c[1], c[2], c[3]))


# ---------------------------------------------------------------------------
# Rows
# ---------------------------------------------------------------------------
def row(label, nonce, balance, root, code_hash):
    encoded = bytes(
        encode_account(
            Account(nonce=Uint(nonce), balance=U256(balance), code_hash=code_hash), root
        )
    )
    independent = independent_account_rlp(nonce, balance, root, code_hash)
    if encoded != independent:
        raise SystemExit(
            f"DISAGREEMENT on {label}: specification {encoded.hex()} "
            f"independent {independent.hex()}"
        )
    return f"{label} {nonce} {balance} {root.hex()} {code_hash.hex()} {encoded.hex()}"


authored = [
    row("empty-account", 0, 0, EMPTY_ROOT, EMPTY_CODE_HASH),
    row("nonce-only", 1, 0, EMPTY_ROOT, EMPTY_CODE_HASH),
    row("balance-only", 0, 1, EMPTY_ROOT, EMPTY_CODE_HASH),
    row("max-nonce-max-balance", MAX_NONCE, MAX_BALANCE, EMPTY_ROOT, EMPTY_CODE_HASH),
]

ordered = sorted(
    picked.items(),
    key=lambda kv: (kv[0][1], kv[0][2], 0 if kv[0][0] == "externally-owned" else 1),
)
per_corpus = collections.Counter(v[0] for v in picked.values())
corpus_rows = [
    row(f"corpus-{kind}", nonce, balance, EMPTY_ROOT, code_hash)
    for (kind, _nl, _bl), (_tag, _rel, _name, _addr, nonce, balance, code_hash) in ordered
]

lines = [
    "# Account vectors: <label> <nonce> <balance> <storageRoot> <codeHash> <rlp>",
    "# nonce and balance are decimal; the rest is hex without 0x.",
    "#",
    "# ENCODED BY THE EXECUTABLE SPECIFICATION, not by this project and not by",
    "# a client: ethereum/execution-specs, its own encode_account. An account's",
    "# natural certification is the state trie root; these octets are the",
    "# encoding that root is computed over.",
    "#",
    "# EVERY ROW WAS GATED BEFORE BEING WRITTEN: the specification's octets were",
    "# required to equal those of a minimal RLP encoder that shares no code with",
    "# it or with the implementation under test.",
    "#",
    "# WHERE THE VALUES COME FROM. The corpus is named here rather than left to",
    "# be inferred from the encoder's release, which says nothing about it:",
]
for corpus in CORPORA:
    lines.append(f"#   {corpus.name} @ {corpus_ref(corpus)}")
    lines.append(f"#     supplied {per_corpus.get(corpus.name, 0)} row(s)")
lines += [
    "#",
    "# HOW A ROW IS CHOSEN. Accounts are bucketed by (kind, byte-length of the",
    "# minimal nonce, byte-length of the minimal balance) and ONE is taken per",
    "# bucket. Those axes make the table a scalar-boundary sweep rather than a",
    "# sample: RLP encodes a quantity minimally, so each byte-length is a",
    "# different encoding shape and the table walks them.",
    "#",
    "# THE PICK IS DETERMINISTIC, and that is the point of this generator.",
    "# Within a bucket, candidates are ordered by (corpus, relative path, case",
    "# name, address) and the first is taken -- an ordering over published bytes",
    "# rather than over filesystem order, so two runs on two machines agree.",
    "#",
    "# ONLY ACCOUNTS WITH EMPTY STORAGE ARE TAKEN, because a row records a",
    f"# storage root and any other root must be computed from a trie. {skipped_storage}",
    "# account(s) were skipped for carrying non-zero storage. An account whose",
    "# storage entries are all zero counts as empty: a zero slot is absent from",
    "# the trie, so its root is the empty root.",
    f"# {skipped_range} account(s) were skipped for a nonce or balance out of range.",
    "#",
    "# The empty root and the empty code hash are derived here from first",
    "# principles -- the digest of the RLP of the empty byte string, and the",
    "# digest of no bytes -- never recalled.",
    "#",
    f"# {len(authored)} authored row(s) and {len(corpus_rows)} corpus row(s), from {scanned} accounts.",
]

OUT.write_text("\n".join(lines + authored + corpus_rows) + "\n")
print(
    f"wrote {len(authored)} authored + {len(corpus_rows)} corpus rows -> {OUT}",
    file=sys.stderr,
)
for tag, n in sorted(per_corpus.items()):
    print(f"  {tag}: {n} row(s)", file=sys.stderr)
