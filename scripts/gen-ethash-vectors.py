#!/usr/bin/env python3
"""Generate the ethash vector table from a reference client's own test data.

Usage: scripts/gen-ethash-vectors.py <reference-corpus-dir> <output.txt>

THE CORPUS IS A PARAMETER, and the ref it came from is named in the output
file's own header rather than here -- this file is committed and public, so no
path to a local clone may appear in it.

WHY A CLIENT'S TEST FILE AND NOT A PUBLISHED FIXTURE TIER. The published tier
for this layer is `PoWTests/ethash_tests.json`, and it is TWO cases, both at
epoch zero, byte-identical across all three corpora that carry it. It states no
cache contents and no dataset contents at all, so it cannot certify either
construction directly -- only the digest at the end of them. go-ethereum-pow
states a whole expected cache and a whole expected dataset as literals, at a
size small enough to be a test rather than a benchmark, which is the only
byte-exact statement of those two artifacts available anywhere in the corpus.

THE GATE: every extracted vector is REGENERATED here, from the seed up, by the
independent implementation below -- keccak from pycryptodome, no shared code
with the Scala under test and none with the Go it was extracted from -- and
required to match the extracted bytes exactly before anything is written. So a
row in the output is a value two implementations agree on, not a transcription.

WHAT IS NOT COVERED, stated because a partial harvest that stays quiet reads as
a full one: the caches are 1024 bytes and the dataset 32768, against 16776896
and 1073739904 for a real first epoch. Size changes no branch in either
construction -- an item is a pure function of the cache and its index -- but it
is a bound on what these rows establish and the suite says so where it uses
them. ECIP-1099 appears in no vector here; go-ethereum-pow predates it.
"""
import pathlib
import re
import sys

from Crypto.Hash import keccak

if len(sys.argv) != 3:
    print(__doc__.strip().splitlines()[2], file=sys.stderr)
    raise SystemExit(2)

CORPUS = pathlib.Path(sys.argv[1])
OUT = pathlib.Path(sys.argv[2])

SOURCE = CORPUS / "ethereum/go-ethereum-pow/consensus/ethash/algorithm_test.go"
REF = "ethereum/go-ethereum-pow @ v1.10.26 (e5eb32acee19cc9fca6a03b10283b7484246b15a, 2022-11-03)"

EPOCH_LENGTH = 30000
HASH_BYTES = 64
MIX_BYTES = 128
HASH_WORDS = 16
CACHE_ROUNDS = 3
DATASET_PARENTS = 256
ACCESSES = 64
FNV_PRIME = 0x01000193
U32 = 0xFFFFFFFF


def keccak512(data):
    return keccak.new(digest_bits=512, data=data).digest()


def keccak256(data):
    return keccak.new(digest_bits=256, data=data).digest()


def seed_hash(epoch):
    """The seed for an epoch, counted in LEGACY epochs.

    The block passed is the epoch's first, `epoch * length + 1`, which is what
    go-ethereum-pow's test does at the call site and what ECIP-1099 later makes
    load-bearing by keeping this divisor at the old length.
    """
    block = epoch * EPOCH_LENGTH + 1
    seed = b"\x00" * 32
    if block < EPOCH_LENGTH:
        return seed
    for _ in range(block // EPOCH_LENGTH):
        seed = keccak256(seed)
    return seed


def generate_cache(size, seed):
    rows = size // HASH_BYTES
    cache = [keccak512(seed)]
    for i in range(1, rows):
        cache.append(keccak512(cache[i - 1]))
    for _ in range(CACHE_ROUNDS):
        for i in range(rows):
            first = cache[(i - 1 + rows) % rows]
            second = cache[int.from_bytes(cache[i][0:4], "little") % rows]
            cache[i] = keccak512(bytes(a ^ b for a, b in zip(first, second)))
    return b"".join(cache)


def words_of(raw):
    return [int.from_bytes(raw[i : i + 4], "little") for i in range(0, len(raw), 4)]


def bytes_of(words):
    return b"".join(w.to_bytes(4, "little") for w in words)


def fnv(a, b):
    return ((a * FNV_PRIME) ^ b) & U32


def dataset_item(cache_words, index):
    rows = len(cache_words) // HASH_WORDS
    mix = list(cache_words[(index % rows) * HASH_WORDS :][:HASH_WORDS])
    mix[0] ^= index
    mix = words_of(keccak512(bytes_of(mix)))
    for parent in range(DATASET_PARENTS):
        row = fnv(index ^ parent, mix[parent % HASH_WORDS]) % rows
        parent_words = cache_words[row * HASH_WORDS :][:HASH_WORDS]
        mix = [fnv(m, p) for m, p in zip(mix, parent_words)]
    return words_of(keccak512(bytes_of(mix)))


def generate_dataset(cache_words, size):
    return b"".join(
        bytes_of(dataset_item(cache_words, i)) for i in range(size // HASH_BYTES)
    )


def hashimoto(seal_hash, nonce, size, fetch):
    rows = size // MIX_BYTES
    seed = keccak512(seal_hash + nonce[::-1])
    seed_head = int.from_bytes(seed[0:4], "little")
    seed_words = words_of(seed)
    mix = [seed_words[i % HASH_WORDS] for i in range(MIX_BYTES // 4)]
    for access in range(ACCESSES):
        block = fnv(access ^ seed_head, mix[access % len(mix)]) % rows
        fetched = []
        for half in range(MIX_BYTES // HASH_BYTES):
            fetched += fetch(2 * block + half)
        mix = [fnv(m, f) for m, f in zip(mix, fetched)]
    compressed = [
        fnv(fnv(fnv(mix[i], mix[i + 1]), mix[i + 2]), mix[i + 3])
        for i in range(0, len(mix), 4)
    ]
    mix_hash = bytes_of(compressed)
    return mix_hash, keccak256(seed + mix_hash)


def literal_after(block, field):
    """The concatenated hex literal following `field:` inside a Go struct block."""
    at = block.index(field + ":")
    tail = block[at:]
    end = tail.index("),")
    return "".join(re.findall(r'"([0-9a-fA-F]+)"', tail[:end]))


def go_block(text, func, field):
    """Each table record in one Go test function, as (epoch, bytes).

    Split on the record opener rather than on a leading field: the two tables
    order their struct fields differently -- `size` precedes `epoch` in one and
    follows it in the other -- so keying on whichever field comes first reads
    one table and silently returns nothing for the other.
    """
    start = text.index("func " + func + "(")
    end = text.index("\nfunc ", start + 1)
    body = text[start:end]
    out = []
    for chunk in body.split("\n\t\t{\n")[1:]:
        epoch = int(re.search(r"epoch:\s*(\d+)", chunk).group(1))
        out.append((epoch, bytes.fromhex(literal_after(chunk, field))))
    return out


text = SOURCE.read_text()

caches = go_block(text, "TestCacheGeneration", "cache")
datasets = go_block(text, "TestDatasetGeneration", "dataset")
if not caches or not datasets:
    print("extracted nothing; the reference test file's shape has moved", file=sys.stderr)
    raise SystemExit(1)

rows = []

for epoch, expected in caches:
    built = generate_cache(len(expected), seed_hash(epoch))
    if built != expected:
        print(f"cache epoch {epoch}: regeneration disagrees with the extracted literal", file=sys.stderr)
        raise SystemExit(1)
    rows.append(f"cache {epoch} {seed_hash(epoch).hex()} {expected.hex()}")

for epoch, expected in datasets:
    cache_bytes = dict(caches)[epoch]
    built = generate_dataset(words_of(cache_bytes), len(expected))
    if built != expected:
        print(f"dataset epoch {epoch}: regeneration disagrees with the extracted literal", file=sys.stderr)
        raise SystemExit(1)
    rows.append(f"dataset {epoch} {len(cache_bytes)} {expected.hex()}")

# The mixing vector, whose four short literals sit in the body of TestHashimoto
# rather than in a table. Extracted the same way and regenerated over BOTH item
# sources, because the two agreeing is the only check a real dataset can get.
body = text[text.index("func TestHashimoto(") :]
body = body[: body.index("\nfunc ")]
short = re.findall(r'hexutil\.MustDecode\("0x([0-9a-fA-F]+)"\)', body)
seal_hash, want_mix, want_result = (bytes.fromhex(s) for s in short[:3])
nonce = int(re.search(r"nonce\s*:?=\s*uint64\((\d+)\)", body).group(1)).to_bytes(8, "big")
size = 32 * 1024

cache_words = words_of(generate_cache(1024, b"\x00" * 32))
dataset_words = words_of(generate_dataset(cache_words, size))

light = hashimoto(seal_hash, nonce, size, lambda i: dataset_item(cache_words, i))
full = hashimoto(
    seal_hash, nonce, size, lambda i: dataset_words[i * HASH_WORDS :][:HASH_WORDS]
)
if light != full:
    print("the light and full paths disagree in the generator itself", file=sys.stderr)
    raise SystemExit(1)
if light[0] != want_mix or light[1] != want_result:
    print("regeneration disagrees with the extracted mixing vector", file=sys.stderr)
    raise SystemExit(1)

rows.append(
    "hashimoto 1024 %d %s %s %s %s"
    % (size, seal_hash.hex(), nonce.hex(), want_mix.hex(), want_result.hex())
)

OUT.write_text(
    "\n".join(
        [
            "# Ethash vectors. One row per artifact, all hex, fields space-separated:",
            "#   cache     <epoch> <seed> <cacheBytes>",
            "#   dataset   <epoch> <cacheSize> <datasetBytes>",
            "#   hashimoto <cacheSize> <datasetSize> <sealHash> <nonce> <mixHash> <result>",
            "#",
            "# Extracted from a reference client's own test data and then REGENERATED",
            "# from the seed up by an implementation sharing no code with either the",
            "# client or the code under test. A row is written only where the two agree.",
            "#",
            "# Source, cited by immutable ref:",
            "#   " + REF,
            "#",
            "# The `hashimoto` row was verified over BOTH item sources -- regenerating",
            "# each item from the cache, and reading it from a fully built dataset --",
            "# and the two agree with each other and with the client. That equality is",
            "# what a real epoch's dataset cannot be given, since no published value",
            "# states one, so it is the property the suite checks at this size.",
            "#",
            "# BOUND: the caches are 1024 bytes and the dataset 32768, against 16776896",
            "# and 1073739904 for a real first epoch. Size changes no branch in either",
            "# construction. ECIP-1099 appears in no row here -- the client predates it.",
            "",
        ]
        + rows
    )
    + "\n"
)
print(f"wrote {len(rows)} rows to {OUT}")
