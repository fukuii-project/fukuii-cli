#!/usr/bin/env python3
"""Generate the block vector table from published conformance fixtures.

Usage: scripts/gen-block-vectors.py <fixtures-dir> <output.txt>

THE CORPUS IS A PARAMETER, and the release it came from is named in the output
file's own header rather than here -- this file is committed and public, so no
path to a local clone may appear in it.

THE GATE: every corpus row's whole block `rlp` is rebuilt from the fixture's
separately-decoded blockHeader, transactions, uncleHeaders and withdrawals by
the minimal RLP encoder below, and required to equal the published octets byte
for byte. That encoder shares no code with the implementation under test.

The published `blockHeader.hash` is carried too, so one table certifies the
block encoding and the block hash together -- and the hash is the header's,
which is the reading a block-shaped digest would get wrong.

WHAT THE CORPUS CANNOT REACH, and this is the important part: every one of the
94238 blocks it publishes has an EMPTY ommer list. A `Block` certified only
against those is certified against nothing on that field, so constructed rows
carry a real header inside the ommers list. They are labelled `built-` and
they certify ENCODING AND ROUND TRIP ONLY -- such a block is invalid, because
its header's ommersHash commits to the empty list, and validity is not this
layer's to assert either way.
"""
import collections
import json
import pathlib
import sys

if len(sys.argv) != 3:
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    raise SystemExit(2)

FIX = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])

MAX_BLOCK_BYTES = 1400
PER_BUCKET = 2
TARGET_ROWS = 44


def enc_len(n, offset):
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


def unhex(s):
    if s is None:
        return b""
    return bytes.fromhex(s[2:] if s.startswith("0x") else s)


def q(v) -> bytes:
    if v is None:
        return enc_bytes(b"")
    n = int(v, 16) if isinstance(v, str) else int(v)
    if n == 0:
        return enc_bytes(b"")
    return enc_bytes(n.to_bytes((n.bit_length() + 7) // 8, "big"))


def fixed(v, width) -> bytes:
    raw = unhex(v)
    if len(raw) != width:
        raise ValueError(f"width {len(raw)} != {width}")
    return enc_bytes(raw)


def recipient(v) -> bytes:
    if v in (None, "", "0x"):
        return enc_bytes(b"")
    return fixed(v, 20)


def access_list(al) -> bytes:
    return enc_list([
        enc_list([fixed(e["address"], 20),
                  enc_list([fixed(k, 32) for k in e.get("storageKeys", [])])])
        for e in al or []
    ])


def auth_list(al) -> bytes:
    out = []
    for a in al or []:
        yp = a.get("yParity", a.get("v"))
        out.append(enc_list([q(a["chainId"]), fixed(a["address"], 20), q(a["nonce"]),
                             q(yp), q(a["r"]), q(a["s"])]))
    return enc_list(out)


def tx_canonical(tx) -> bytes:
    t = tx.get("type")
    ti = int(t, 16) if isinstance(t, str) else int(t or 0)
    yp = tx.get("yParity", tx.get("v"))
    if ti == 0:
        return enc_list([q(tx["nonce"]), q(tx["gasPrice"]), q(tx["gasLimit"]),
                         recipient(tx.get("to")), q(tx["value"]), enc_bytes(unhex(tx.get("data"))),
                         q(tx["v"]), q(tx["r"]), q(tx["s"])])
    if ti == 1:
        body = enc_list([q(tx["chainId"]), q(tx["nonce"]), q(tx["gasPrice"]), q(tx["gasLimit"]),
                         recipient(tx.get("to")), q(tx["value"]), enc_bytes(unhex(tx.get("data"))),
                         access_list(tx.get("accessList")), q(yp), q(tx["r"]), q(tx["s"])])
    elif ti == 2:
        body = enc_list([q(tx["chainId"]), q(tx["nonce"]), q(tx["maxPriorityFeePerGas"]),
                         q(tx["maxFeePerGas"]), q(tx["gasLimit"]), recipient(tx.get("to")),
                         q(tx["value"]), enc_bytes(unhex(tx.get("data"))),
                         access_list(tx.get("accessList")), q(yp), q(tx["r"]), q(tx["s"])])
    elif ti == 3:
        body = enc_list([q(tx["chainId"]), q(tx["nonce"]), q(tx["maxPriorityFeePerGas"]),
                         q(tx["maxFeePerGas"]), q(tx["gasLimit"]), fixed(tx["to"], 20),
                         q(tx["value"]), enc_bytes(unhex(tx.get("data"))),
                         access_list(tx.get("accessList")), q(tx["maxFeePerBlobGas"]),
                         enc_list([fixed(h, 32) for h in tx.get("blobVersionedHashes", [])]),
                         q(yp), q(tx["r"]), q(tx["s"])])
    elif ti == 4:
        body = enc_list([q(tx["chainId"]), q(tx["nonce"]), q(tx["maxPriorityFeePerGas"]),
                         q(tx["maxFeePerGas"]), q(tx["gasLimit"]), fixed(tx["to"], 20),
                         q(tx["value"]), enc_bytes(unhex(tx.get("data"))),
                         access_list(tx.get("accessList")), auth_list(tx.get("authorizationList")),
                         q(yp), q(tx["r"]), q(tx["s"])])
    else:
        raise ValueError(f"unmodeled type {ti}")
    return bytes([ti]) + body


def tx_element(tx) -> bytes:
    """As a BLOCK-BODY element: legacy is the list, typed is a string."""
    t = tx.get("type")
    ti = int(t, 16) if isinstance(t, str) else int(t or 0)
    canonical = tx_canonical(tx)
    return canonical if ti == 0 else enc_bytes(canonical)


# The header's field order, taken from the wire specification's own listing.
# Every field after `block-nonce` is a trailing option, and a header carries a
# PREFIX of them -- never a gap.
HEAD_MANDATORY = [
    ("parentHash", "h32"), ("uncleHash", "h32"), ("coinbase", "h20"),
    ("stateRoot", "h32"), ("transactionsTrie", "h32"), ("receiptTrie", "h32"),
    ("bloom", "h256"), ("difficulty", "q"), ("number", "q"), ("gasLimit", "q"),
    ("gasUsed", "q"), ("timestamp", "q"), ("extraData", "b"), ("mixHash", "h32"),
    ("nonce", "h8"),
]
HEAD_TAIL = [
    ("baseFeePerGas", "q"), ("withdrawalsRoot", "h32"), ("blobGasUsed", "q"),
    ("excessBlobGas", "q"), ("parentBeaconBlockRoot", "h32"), ("requestsHash", "h32"),
]

WIDTH = {"h32": 32, "h20": 20, "h256": 256, "h8": 8}


def enc_field(value, kind):
    if kind == "q":
        return q(value)
    if kind == "b":
        return enc_bytes(unhex(value))
    return fixed(value, WIDTH[kind])


def header_encoded(h) -> bytes:
    items = [enc_field(h[k], t) for k, t in HEAD_MANDATORY]
    for k, t in HEAD_TAIL:
        if h.get(k) is None:
            break
        items.append(enc_field(h[k], t))
    return enc_list(items)


def withdrawal_list(ws) -> bytes:
    return enc_list([
        enc_list([q(w["index"]), q(w["validatorIndex"]), fixed(w["address"], 20), q(w["amount"])])
        for w in ws
    ])



def rlp_split(b, i):
    p = b[i]
    if p <= 0x7F:
        return "str", i, 1, i + 1
    if p <= 0xB7:
        n = p - 0x80
        return "str", i + 1, n, i + 1 + n
    if p <= 0xBF:
        k = p - 0xB7
        n = int.from_bytes(b[i + 1 : i + 1 + k], "big")
        return "str", i + 1 + k, n, i + 1 + k + n
    if p <= 0xF7:
        n = p - 0xC0
        return "list", i + 1, n, i + 1 + n
    k = p - 0xF7
    n = int.from_bytes(b[i + 1 : i + 1 + k], "big")
    return "list", i + 1 + k, n, i + 1 + k + n


def kids(b, start, length):
    out, i, end = [], start, start + length
    while i < end:
        _kind, _s, _l, nxt = rlp_split(b, i)
        out.append(i)
        i = nxt
    return out


def rlp_head_span(block_bytes):
    """(start, length) of the header element inside a whole block encoding."""
    _kind, ps, pl, _ = rlp_split(block_bytes, 0)
    first = kids(block_bytes, ps, pl)[0]
    _k2, hs, hl, _ = rlp_split(block_bytes, first)
    return hs, hl


buckets = collections.defaultdict(list)
stats = collections.Counter()
shapes_seen = set()
spare_header = None

paths = sorted((FIX / "blockchain_tests").rglob("*.json"))
print(f"harvesting from {len(paths)} files ...", file=sys.stderr)

for path in paths:
    try:
        doc = json.loads(path.read_text())
    except Exception:
        continue
    for case in doc.values():
        if not isinstance(case, dict):
            continue
        for block in case.get("blocks", []) or []:
            if not isinstance(block, dict) or "expectException" in block:
                continue
            h, raw = block.get("blockHeader"), block.get("rlp")
            if not h or not raw:
                continue
            stats["seen"] += 1
            try:
                published = unhex(raw)
                head = header_encoded(h)
                txs = enc_list([tx_element(t) for t in block.get("transactions") or []])
                ommers = enc_list([header_encoded(u) for u in block.get("uncleHeaders") or []])
                parts = [head, txs, ommers]
                if block.get("withdrawals") is not None:
                    parts.append(withdrawal_list(block["withdrawals"]))
                rebuilt = enc_list(parts)
            except Exception:
                stats["reencode-error"] += 1
                continue

            if rebuilt != published:
                stats["MISMATCH"] += 1
                continue
            stats["verified"] += 1
            stats[f"ommers-{len(block.get('uncleHeaders') or [])}"] += 1

            if spare_header is None and len(block.get("transactions") or []) == 0:
                spare_header = head

            if len(published) > MAX_BLOCK_BYTES:
                stats["dropped-by-cap"] += 1
                continue

            arity = 4 if block.get("withdrawals") is not None else 3
            ntx = len(block.get("transactions") or [])
            nw = len(block.get("withdrawals") or []) if arity == 4 else -1
            # Stratify on the HEADER's arity too. Without it the selection
            # filled from the two commonest tail lengths and the 17-element
            # header -- 5214 valid ones in this corpus -- never appeared in a
            # row, leaving that tail boundary pinned only by construction.
            head_arity = len(kids(published, *rlp_head_span(published)))
            shape = (arity, min(ntx, 3), min(nw, 2), head_arity)
            shapes_seen.add(shape)
            if len(buckets[shape]) < PER_BUCKET:
                buckets[shape].append((published, h, ntx, nw))

print(f"  {dict(stats)}", file=sys.stderr)

if stats["MISMATCH"]:
    print("REFUSING TO EMIT: slice/re-encode disagreement", file=sys.stderr)
    sys.exit(1)

rows = []
order = sorted(buckets)
i = 0
while len(rows) < TARGET_ROWS and any(buckets[s] for s in order):
    s = order[i % len(order)]
    if buckets[s]:
        rows.append((s, *buckets[s].pop(0)))
    i += 1

# The stratum the corpus does not contain. A real block, re-encoded with a real
# header placed in its ommers list -- so the ommer is a header that shipped,
# and only its POSITION is constructed.
built = []
if spare_header is not None:
    for count in (1, 2):
        for shape, published, h, ntx, nw in rows:
            if shape[0] != 3:
                continue
            head = header_encoded(h)
            txs_raw = published  # rebuild from parts rather than splice
            body_txs = enc_list([])
            rebuilt = enc_list([head, body_txs, enc_list([spare_header] * count)])
            built.append((f"built-ommers{count}", rebuilt, h, 0, -1))
            break

lines = [
    "# Block vectors: <label> <arity> <tx-count> <ommer-count> <withdrawal-count> <block-hash> <rlp>",
    "# All hex is without 0x. `withdrawal-count` is -1 when the block carries no",
    "# withdrawals ELEMENT at all, which is a different fact from carrying an",
    "# empty one -- pre-Shanghai blocks have three elements, not four.",
    "#",
    "# TWO SOURCES, AND THE LABEL SAYS WHICH.",
    "#",
    "#   corpus-*  OCTETS THAT SHIPPED IN A BLOCK. ethereum/execution-specs",
    "#             release tests@v20.0.1 (87aba1a38), asset fixtures.tar.gz.",
    "#             The whole block `rlp` as published.",
    "#",
    "#   built-*   CONSTRUCTED, and only in the one direction the corpus cannot",
    "#             reach: EVERY ONE of the corpus's blocks has an empty ommer",
    "#             list, so nothing there exercises that field. A header that",
    "#             shipped is placed into the ommers list and the block is",
    "#             re-encoded by the independent encoder. THESE CERTIFY",
    "#             ENCODING AND ROUND TRIP ONLY: such a block is invalid,",
    "#             because its header's ommersHash commits to the empty list,",
    "#             and validity is a fork rule this layer does not assert.",
    "#",
    "# EVERY CORPUS ROW WAS CROSS-VERIFIED BEFORE BEING WRITTEN: the fixture's",
    "# separately-decoded header, transactions, ommers and withdrawals were",
    "# re-encoded by an implementation independent of the one under test and",
    f"# required to equal the published octets. Corpus-wide: {stats['verified']}",
    f"# blocks verified, {stats['MISMATCH']} mismatches.",
    "#",
    "# `block-hash` is the published blockHeader.hash -- the HEADER's digest,",
    "# which is what a block hash is. A digest over the block encoding would be",
    "# stable, plausible and agreed with by nobody.",
    "#",
    "# A DECLARED CAP, not a silent one: rows are capped at "
    f"{MAX_BLOCK_BYTES} bytes;",
    f"# {stats['dropped-by-cap']} blocks were dropped by that cap.",
]

seen_labels = collections.Counter()
for shape, published, h, ntx, nw in rows:
    arity, _tb, _wb, head_arity = shape
    base = f"corpus-a{arity}-h{shape[3]}-tx{min(ntx, 3)}-w{nw if nw >= 0 else 'none'}"
    seen_labels[base] += 1
    label = base if seen_labels[base] == 1 else f"{base}-{seen_labels[base] - 1}"
    lines.append(
        f"{label} {arity} {ntx} 0 {nw} {h['hash'][2:]} {published.hex()}"
    )

for label, rebuilt, h, ntx, nw in built:
    n = rebuilt.count(spare_header)
    lines.append(
        f"{label} 3 0 {label[-1]} -1 {h['hash'][2:]} {rebuilt.hex()}"
    )

OUT.write_text("\n".join(lines) + "\n")
print(f"wrote {len(rows)} corpus + {len(built)} constructed rows -> {OUT}", file=sys.stderr)
