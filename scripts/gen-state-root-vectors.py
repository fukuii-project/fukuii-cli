#!/usr/bin/env python3
"""Generate the two-level state-root vector table from published fixtures.

Usage: scripts/gen-state-root-vectors.py <blockchain_tests-dir> <output.txt>

THE CORPUS IS A PARAMETER, and the release it came from is named in the output
file's own header rather than here -- this file is committed and public, so no
path to a local clone may appear in it.

WHAT THIS CERTIFIES THAT A FLAT TRIE VECTOR CANNOT. ethereum/tests TrieTests
drives one trie over opaque byte strings. A state root is TWO levels: each
account's own storage trie is rooted first, that root is embedded in the
account's RLP, and only then does the account leaf exist to be inserted into
the account trie. An implementation can reproduce every flat vector and still
build this wrong -- by ordering the two levels the other way round, by securing
one level and not the other, or by encoding an account's four fields in the
wrong order. Nothing in TrieTests reaches any of that.

THE GATE: every row's `pre` allocation is driven through the executable
specification's own state (`ethereum.state_mpt`), and the state root it
computes is required to equal the fixture's published
`genesisBlockHeader.stateRoot` before the row is emitted. A row whose two
sources disagree is reported and NOT written.

TWO ENCODING RULES THAT ARE EASY TO GET WRONG AND ARE DECLARED HERE RATHER
THAN LEFT TO BE REDISCOVERED:

  * A storage slot holding ZERO is ABSENT from the storage trie -- it is not a
    leaf holding a zero. Fixtures do carry such entries, so this script drops
    them. TWO COUNTS ARE PRINTED AND THEY MEASURE DIFFERENT THINGS: one is
    corpus-wide, over every case scanned, and is what establishes that such
    entries exist at all; the other is scoped to the rows this table emitted,
    and reads 0 whenever no selected case carried one. A reader who takes the
    second for the first concludes the corpus is clean from a number that
    never looked at it. NOTE WHERE THAT RULE LIVES: the trie takes
    opaque bytes and has no notion of a zero-valued word, so this is not the
    trie's rule to enforce and no row here can test it. It belongs to whichever
    layer later writes storage from typed values, and that layer does not exist
    yet. An implementation that writes RLP(0) = 0x80 there produces a leaf
    where there must be none, and a storage root no client agrees with.

  * A storage slot's key is the 32-byte slot number, LEFT-PADDED, and the trie
    then secures it. Fixtures write these keys minimally (`0x00`, not
    `0x00..00`), so a reader that takes them at their published width hashes
    the wrong pre-image.

WHAT THE CORPUS CANNOT REACH: these are all GENESIS allocations, so every
account's storage trie is built by direct insertion and none of them is
reached through a state transition. Nothing here exercises a storage root
CHANGING under an account that already exists, which is the ordering hazard
the two-level rule exists for. That case needs a fixture's `postState` against
its last block's header, and it is not attempted here.
"""
import collections
import json
import pathlib
import sys

if len(sys.argv) != 3:
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    raise SystemExit(2)

ROOT = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])

try:
    import ethereum.state_mpt as S
    from ethereum.state import Account
    from ethereum_types.bytes import Bytes20, Bytes32
    from ethereum_types.numeric import U256, Uint
except ImportError:  # pragma: no cover - environment guard
    print(
        "the executable specification is not importable; put its src/ on "
        "PYTHONPATH (and redirect __pycache__ so the corpus is not mutated)",
        file=sys.stderr,
    )
    raise SystemExit(2)

# The caps bound the table's size, not its reach, and BOTH of these were found
# to bound its reach instead. An account cap doubles as a storage cap, because
# the fixtures carrying many storage slots also carry many accounts. A CODE-SIZE
# cap was worse: the accounts that carry a lot of storage are contracts, which
# carry a lot of code, so capping code emptied the storage-rich tiers entirely
# while looking like it only bounded file size. Check the tier histogram printed
# by this script after changing either -- a silently leaf-only storage tier
# passes every row and certifies nothing about the second level.
MAX_ACCOUNTS = 30
MAX_SLOTS = 120
PER_BUCKET = 2


def storage_tier(slots: int) -> str:
    """A storage trie of one slot is a single LEAF and exercises no structure.
    Bucketing by tier and taking the richest in each is what keeps the table
    from filling up with them -- which is what a first-match selection does,
    because the small cases sort first."""
    if slots == 0:
        return "none"
    if slots <= 3:
        return "leafy"
    if slots <= 15:
        return "branching"
    return "deep"


def release_id(path: pathlib.Path) -> str:
    """The release directory name, walked up to the one holding `fixtures`."""
    for parent in [path, *path.parents]:
        if parent.name == "fixtures":
            return parent.parent.name
    return "UNKNOWN"


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
    return int(s, 16) if isinstance(s, str) else int(s)


def minimal(n: int) -> str:
    """Hex without 0x, minimal but padded to a WHOLE BYTE (`00` for zero).

    The padding is not cosmetic: a hex decoder that reads whole bytes rejects an
    odd-length string outright, so emitting a bare `0` or `1` would make every
    such row unreadable rather than merely ugly.
    """
    s = format(n, "x")
    return "0" + s if len(s) % 2 else s


rows = []
mismatches = []
dropped_zero_slots = 0
corpus_zero_slots = 0
corpus_zero_cases = 0
dropped_by_cap = collections.Counter()
buckets = collections.Counter()
scanned = 0

# Pass 1: qualify every case cheaply and remember only its shape, so that
# pass 2 can take the RICHEST in each bucket rather than the first one seen.
candidates = []
for path in sorted(ROOT.glob("**/*.json")):
    fork = None
    for part in path.parts:
        if part.startswith("for_"):
            fork = part[4:]
            break
    try:
        data = json.loads(path.read_text())
    except Exception:
        continue
    for name, case in data.items():
        if "pre" not in case or "genesisBlockHeader" not in case:
            continue
        scanned += 1
        pre = case["pre"]
        # Corpus-wide, over every scanned case rather than the selected ones.
        # The dropped count below is scoped to this table and is 0 whenever no
        # selected case happens to carry one -- which is equally what a corpus
        # holding none would print, so it cannot evidence that the corpus holds
        # any. This counter is what does.
        case_zero = sum(
            1
            for a in pre.values()
            for v in (a.get("storage") or {}).values()
            if as_int(v) == 0
        )
        if case_zero:
            corpus_zero_slots += case_zero
            corpus_zero_cases += 1
        if len(pre) > MAX_ACCOUNTS:
            dropped_by_cap["accounts"] += 1
            continue
        slots = sum(len(a.get("storage", {})) for a in pre.values())
        if slots > MAX_SLOTS:
            dropped_by_cap["slots"] += 1
            continue
        codes = sum(1 for a in pre.values() if unhex(a.get("code", "0x")))
        candidates.append(
            ((fork, storage_tier(slots), codes > 0), -slots, str(path), name)
        )

selected = []
for shape, negslots, pathstr, name in sorted(candidates):
    if buckets[shape] >= PER_BUCKET:
        continue
    buckets[shape] += 1
    selected.append((shape, pathstr, name))

# Pass 2: compute and gate only the selected cases.
seen_labels = collections.Counter()
by_file = collections.defaultdict(list)
for shape, pathstr, name in selected:
    by_file[pathstr].append((shape, name))

for pathstr, wanted in sorted(by_file.items()):
    path = pathlib.Path(pathstr)
    fork = wanted[0][0][0]
    data = json.loads(path.read_text())
    for shape, name in wanted:
        case = data[name]
        pre = case["pre"]
        slots = sum(len(a.get("storage", {})) for a in pre.values())

        published = unhex(case["genesisBlockHeader"]["stateRoot"])
        state = S.State()
        local_dropped = 0
        emitted = []
        for address, account in sorted(pre.items()):
            addr = Bytes20(unhex(address))
            code = unhex(account.get("code", "0x"))
            code_hash = S.store_code(state, code)
            S.set_account(
                state,
                addr,
                Account(
                    nonce=Uint(as_int(account.get("nonce", "0x0"))),
                    balance=U256(as_int(account.get("balance", "0x0"))),
                    code_hash=code_hash,
                ),
            )
            kept = []
            for slot, value in sorted(account.get("storage", {}).items()):
                key = as_int(slot)
                val = as_int(value)
                S.set_storage(
                    state, addr, Bytes32(key.to_bytes(32, "big")), U256(val)
                )
                if val == 0:
                    local_dropped += 1
                else:
                    kept.append((key, val))
            emitted.append((address, account, bytes(code_hash), kept))

        computed = S.state_root(state)
        base = f"{fork}-{path.stem}-{len(pre)}a{slots}s"
        seen_labels[base] += 1
        label = base if seen_labels[base] == 1 else f"{base}-{seen_labels[base] - 1}"
        if computed != published:
            mismatches.append((label, published.hex(), computed.hex()))
            continue
        dropped_zero_slots += local_dropped
        rows.append((label, published, emitted))

if mismatches:
    print("DISAGREEMENT -- rows NOT emitted:", file=sys.stderr)
    for label, expected, got in mismatches[:20]:
        print(f"  {label}: published {expected} computed {got}", file=sys.stderr)

with_storage = sum(1 for _, _, accts in rows if any(k for _, _, _, k in accts))
with_code = sum(
    1
    for _, _, accts in rows
    if any(c != bytes(S.EMPTY_CODE_HASH) for _, _, c, _ in accts)
)
total_slots = sum(len(k) for _, _, accts in rows for _, _, _, k in accts)
total_accounts = sum(len(accts) for _, _, accts in rows)
tier_spread = collections.Counter(
    storage_tier(sum(len(k) for _, _, _, k in accts)) for _, _, accts in rows
)

lines = [
    "# State-root vectors: the TWO-LEVEL root, which no flat trie vector reaches.",
    "#",
    "# Record format, one vector per `case` line followed by its accounts:",
    "#   case <label> <state-root>",
    "#   acct <address> <nonce> <balance> <code-hash> <slot-count>",
    "#   slot <key> <value>",
    "#",
    "# All hex is without 0x and is a whole number of bytes. `nonce`, `balance`,",
    "# `key` and `value` are minimal width padded to a byte (`00` for zero);",
    "# `address` and `code-hash` are full width. A slot's",
    "# `key` is the slot NUMBER -- left-pad it to 32 bytes before the trie",
    "# secures it, because these are published minimally and hashing them at",
    "# their published width hashes the wrong pre-image.",
    "#",
    "# THE ROW CARRIES THE CODE HASH, NEVER THE CODE, and that is the",
    "# specification's own distinction rather than a size economy: the trie",
    "# commits to an account's code hash and never to its contents. Carrying the",
    "# contents would also have bounded this table's reach, because the accounts",
    "# holding the most storage are contracts holding the most code.",
    "#",
    f"# SOURCE: ethereum/execution-specs fixture release {release_id(ROOT)},",
    "# blockchain_tests/, each vector's genesis `pre` allocation against that",
    "# fixture's own published genesisBlockHeader.stateRoot.",
    "#",
    "# THE GATE: every row was driven through the executable specification's own",
    "# state (ethereum.state_mpt) and its computed state root required to equal",
    "# the published one before the row was written. Rows are emitted only on",
    "# agreement.",
    "#",
    "# WHY TWO LEVELS IS A SEPARATE BAR: a flat trie vector drives one trie over",
    "# opaque bytes. A state root roots each account's storage trie FIRST,",
    "# embeds that root in the account's RLP, and only then has an account leaf",
    "# to insert. An implementation can reproduce every flat vector and still",
    "# order the two levels wrongly, secure one level and not the other, or",
    "# encode the account's four fields out of order.",
    "#",
    "# A ZERO STORAGE SLOT IS ABSENT, NOT A LEAF HOLDING ZERO. The corpus",
    f"# carries {corpus_zero_slots} such entries across {corpus_zero_cases} of",
    f"# the {scanned} cases scanned, and {dropped_zero_slots} were dropped",
    "# building this table. THE SECOND FIGURE CANNOT EVIDENCE THE FIRST: it",
    "# counts only what the selected rows carried, so a 0 there is what a",
    "# clean corpus and an unlucky selection both print.",
    "# NOTHING HERE TESTS THAT RULE, and the reason is where the rule lives: the",
    "# trie takes opaque bytes and has no notion of a zero-valued word, so it is",
    "# not the trie's to enforce. It belongs to whichever layer later writes",
    "# storage from typed values, and that layer does not exist yet. Recorded",
    "# here so it is inherited rather than rediscovered.",
    "#",
    f"# SPREAD: {len(rows)} vectors, {total_accounts} accounts, {total_slots} storage slots;",
    f"# {with_storage} vectors carry storage and {with_code} carry code. Drawn across the",
    f"# release's fork buckets, from {scanned} candidate cases, at most",
    f"# {PER_BUCKET} per (fork, storage-tier, has-code) bucket and richest first.",
    "#",
    "# DECLARED CAPS, not silent ones. A case is skipped when it carries more",
    f"# than {MAX_ACCOUNTS} accounts ({dropped_by_cap['accounts']} cases) or more than {MAX_SLOTS}",
    f"# storage slots ({dropped_by_cap['slots']} cases). Both bound this table's SIZE, and both",
    "# have previously bound its REACH instead -- an account cap doubles as a",
    "# storage cap, because the fixtures holding the most storage hold the most",
    "# accounts. The storage-tier spread below is what shows whether they are",
    "# currently doing that.",
    f"# TIERS PRESENT: {', '.join(f'{t}={n}' for t, n in sorted(tier_spread.items()))}.",
    "#",
    "# WHAT THIS CANNOT REACH: these are all GENESIS allocations, so every",
    "# storage trie is built by direct insertion. Nothing here exercises a",
    "# storage root CHANGING under an account that already exists, which is the",
    "# ordering hazard the two-level rule exists for. That needs a fixture's",
    "# postState against its last block's header and is not attempted here.",
]
if mismatches:
    lines.append("#")
    lines.append(f"# {len(mismatches)} ROW(S) WERE WITHHELD ON A SOURCE DISAGREEMENT.")

for label, root, accounts in rows:
    lines.append(f"case {label} {root.hex()}")
    for address, account, code_hash, kept in accounts:
        lines.append(
            f"acct {unhex(address).hex()} "
            f"{minimal(as_int(account.get('nonce', '0x0')))} "
            f"{minimal(as_int(account.get('balance', '0x0')))} "
            f"{code_hash.hex()} {len(kept)}"
        )
        for key, value in kept:
            lines.append(f"slot {minimal(key)} {minimal(value)}")

OUT.write_text("\n".join(lines) + "\n")
print(
    f"wrote {len(rows)} vectors ({total_accounts} accounts, {total_slots} slots) -> {OUT}",
    file=sys.stderr,
)
print(
    f"zero-valued storage slots: {corpus_zero_slots} corpus-wide "
    f"across {corpus_zero_cases}/{scanned} cases; "
    f"{dropped_zero_slots} dropped from the emitted table",
    file=sys.stderr,
)
if mismatches:
    raise SystemExit(1)
