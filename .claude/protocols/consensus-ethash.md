# Consensus — Ethash, and ECIP-1099's Etchash

**provenance:** **this file rests on one survey pass, not two, and it is
weaker in one specific way than the four mechanism protocols beside it: no
second party has re-derived any part of it yet.** Read that limit before the
strength below — both are real, and neither cancels the other.

**The pass** read the executable specification and eight production clients
with calibrated absence checks throughout — a nil result here is a reading,
not a silence, because every absence claim was run beside a probe that fired.
Per-claim standing is marked through the file:

| Marker | What it means |
|---|---|
| **MEASURED** | Read from a client's source at the ref named, or from the specification, *and* independently confirmed by a second source or by an executable check the pass ran |
| **READ** | Read once, from one source at the ref named. Correct as a reading of that source; not cross-checked |
| **UNVERIFIED** | Stated because it is worth knowing and was not checked. Treat as a lead |

**Two things back the MEASURED rows that none of the four audited protocols
have, and they bear on the algorithm's bytes specifically, not on the prose
around them.** An independent reimplementation of the whole mixing loop —
different language, different Keccak library, sharing no code with the client
sources it checks against — reproduces `ethereum/go-ethereum-pow`'s expected
cache at two epochs, its expected dataset, and its expected mixing output,
byte for byte, from the seed up. And a second, independent implementation
reproduces the published `PoWTests/ethash_tests.json` tier at eight comparison
points per case. A wrong constant could not survive either.

**That strength is narrow, and saying so is the point of this header.** It
backs the mixing loop, the cache and dataset derivation, and the seal
comparison. It does not touch the ECIP-1099 findings, the storage answer, or
the fork-schedule reading, which rest on reading the proposal and the clients'
source once each — marked READ below, and one item is marked UNVERIFIED.
**Carry the marking on each claim, not this paragraph, when you rely on one.**

**Against the three standings `.claude/protocols/consensus-clique.md` already
names** — never checked (the `currency:` family protocols), checked once, and
checked-then-audited — **this file sits at the second: checked once, with
calibrated controls, and not yet through a second-party read.** That is
exactly where clique, AuRa, QBFT and IBFT2 started before their 2026-08-17
audit; nothing here has had that second pass yet, so treat the un-marked
prose the same way that header treats its own un-audited majority — neither
known-wrong nor known-right, at an unknown rate. Two items are named residuals
for that reason: the force of the ECIP-1099 alignment constraint (a
specification recommendation, read once against one client's comment), and
the `cache_hash` field's meaning in the published fixture, which is derived
here by agreement rather than read from a source that states it.

**Citations below pin an exact ref, not a client-and-file pair.** The four
protocols beside this one cite "a client and a file" and call the result
*"re-runnable rather than permanent"* — correct for what they needed, and a
weaker form than `.claude/rules/evidence-and-citation.md` §1 asks for. This
survey recorded a ref per clone at the moment of citation, so this file uses
the stronger form throughout: a tag where one exists, a commit SHA plus date
otherwise, never a branch. `.claude/reference-corpus.md` is how a clone
assembles the same clients at the same refs.

**No frontmatter, and none is possible.** A file under `.claude/protocols/`
does not auto-load — Claude Code discovers `.claude/rules/`, not this
directory — so `paths:` here would do nothing. Something has to reach this
file by name, whether that is an import, a pointer a reader follows, or a
task brief quoting it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns consensus for
every family and mechanism and instructs its reader to open this file before
acting on anything ethash-shaped. That charter body loads on dispatch; this
file does not. **So an ethash task that never opened this protocol is running
on recollection**, and the charter treats that as the same finding as the
protocol being absent.

**This is a mechanism-fact protocol, not the deferred consensus-change
protocol.** The state-root litmus — the rule deciding whether a change is
consensus at all — is mechanism-neutral, still lives in the charters, and its
canonical home remains a consensus-change protocol this repository does not
have yet. Do not read this file as having closed that gap.

## This mechanism is already built, and that changes what "not a schedule" means here

**The four protocols beside this one commit fukuii to implementing nothing —
this one cannot make that claim, and does not.** `modules/consensus-pow`
already implements ethash and ECIP-1099 as one engine. This file was not
written ahead of a decision nobody had made; the survey behind it was written
minutes before that engine was, and informed it.

**One consequence for how this file is used, stated because the temptation is
specific to this file among the mechanism protocols.** `.claude/agents/forge.md`
§ Provenance and § Authority already forbid citing any document in this
repository as the authority for a value, fukuii's own code included — *"never
validate fukuii against fukuii."* That rule binds hardest exactly here, where
an implementation already exists and agrees with the findings below: the
agreement is not confirmation, because both descend from the same one survey
pass. Treat the built engine as a fourth thing this file is not the authority
for, not as a second source for anything in it.

**What is unchanged from the other four:** `.claude/rules/reference-first.md`'s
"checkable in the field today" test is still what licenses this file to exist
at all — ethash is running code in production clients this project's corpus
already carries, independent of fukuii's own build. And the set of mechanism
protocols is still open: nothing here implies it is closed, and a mechanism
not yet surveyed earns its own file or none. `ls .claude/protocols/` is the
roster.

---

## The mechanism, in the one paragraph the rest depends on

Ethash is memory-hard proof of work. A block's header, with its own two seal
elements removed, is hashed to a seal digest; that digest and a nonce seed a
pseudorandom walk of 64 reads over a large **dataset**, mixing what it reads
into a 128-byte accumulator; the accumulator compresses to a 32-byte *mixed
hash* and a final 32-byte *result*. A header is sealed correctly when the
mixed hash it carries equals the one its own nonce produces and the result is
at most `2^256 / difficulty`. **The dataset is a pure function of a much
smaller cache, and the cache is a pure function of one 32-byte seed** — the
property every divergence below is a different response to, because it means
a validator never needs the dataset and a miner always does. **MEASURED.**

---

## The divergence that matters most: two item sources, one algorithm

**MEASURED.** The mixing loop is stated once, over an abstract item source.
`ethereum/execution-specs` (`ccaaaba58`) declares
`hashimoto(header_hash, nonce, dataset_size, fetch_dataset_item)` and defines
`hashimoto_light` as that same function closed over a cache.

| Path | Item source | Who needs it |
|---|---|---|
| **light** | regenerate the two items each access needs, from the cache | **every validating node** |
| **full** | read them from a built dataset | **a miner, and nothing else** |

- **The specification says so outright**, in `generate_dataset`'s own
  docstring: *"This function is present only for demonstration purposes. It
  is not used while validating blocks."*
- **`ethereum/go-ethereum-pow` @ `v1.10.26`** takes
  `verifySeal(chain, header, fulldag bool) error` and falls back to the cache
  whenever the dataset is not already generated, so even a mining node
  validates from the cache until it has one. `generateDataset` is reached
  only from the sealer's dataset path and from `MakeDataset`, which backs the
  `makedag` command.
- **`besu-eth/besu-etc` @ `eb4248c99`** reaches the dataset only through
  `PoWMinerExecutor` and validates through `EthHash.hashimotoLight`. **READ.**

**Consequence for a client that does not produce blocks: the dataset is not
on the validation path at all.** Building it is a mining prerequisite, never
a consensus one.

---

## Where the implementations genuinely diverge

### 1. The 32-bit index, and the specification is the outlier

**MEASURED.** In the mixing loop the dataset index is `2 * parent + j`.
`execution-specs` computes it in arbitrary precision and comments why —
*"Typecasting `parent` from U32 to Uint as 2*parent + j may overflow U32"* —
while `go-ethereum-pow` keeps `parent` as `uint32` and wraps, and `besu-etc`
keeps it as a Java `int` and wraps identically. They part only where a
dataset exceeds roughly 274 GB, which no network reaches. Where implementers
agree on an unreachable input, that agreement is the one to follow; the
specification's arbitrary-precision reading is correct and unused.

### 2. The target comparison, and one arm that is not a rule

**MEASURED.** Valid iff `result <= 2^256 / difficulty`, in every source
checked: `go-ethereum-pow` refuses on `Cmp(target) > 0`; besu-etc's
`ProofOfWorkValidationRule` refuses on `result.compareTo(target) > 0`;
`execution-specs`' own harness asserts
`Uint.from_be_bytes(result) <= limit // block_difficulty`. Both go-ethereum-pow
and besu-etc refuse a zero difficulty before dividing.

**besu-etc additionally special-cases difficulty 1**, computing the target as
`UInt256.MAX_VALUE` because `2^256` does not fit the fixed-width type it
divides in. **That arm is an artifact of its arithmetic, not a rule of the
mechanism** — computed in arbitrary precision the two forms agree at every
difficulty including one. A client that transcribes besu-etc's branch
literally inherits code that means nothing.

**The `<=` boundary itself is unreachable by any test**, requiring `result`
to equal the target exactly — a 1-in-2^256 event. No corpus can distinguish
`<` from `<=` here; a calibration sweep that flags this as a gap has found
nothing.

### 3. Order of the two checks — divergent, and provably immaterial

**MEASURED.** go-ethereum-pow checks the mixed hash then the target;
besu-etc checks the target then the mixed hash. Same verdict set in both
orders. Worth recording only so neither order is copied as though it carried
meaning.

### 4. The prime search step — an optimization, not a semantic

**MEASURED, and this one was nearly reported as a divergence.** Every client
surveyed walks the candidate size down by `2 * rowWidth` until the row count
is prime. Stepping by `1 * rowWidth` instead reaches the same size at every
epoch checked, 0 through 399: the starting row count is always odd, and even
numbers are never prime, so the single step only visits candidates that
always fail. A seeded defect here is a no-op, and a calibration reporting it
as caught has found nothing.

### 5. Where the seal lives — and two production trees have already dropped it

> **A correction, kept because it is the shape
> `.claude/rules/evidence-and-citation.md` §3 warns about: a grep is a
> search, not a finding.** An earlier reading of this survey reported current
> `go-ethereum` at 1 file against go-ethereum-pow's 5, and concluded the
> reference corpus was thin here. The count was right and the reading was
> wrong. That one file (`signer/fourbyte/4byte.json`) is a data blob of
> function selectors containing the substring `hashimoto`, not an
> implementation — **the honest reading is zero** — and besu's single hit is
> `docs/CHANGELOG_ARCHIVE.md`, likewise zero implementations.

**MEASURED, calibrated** — token `hashimoto`, whole tree,
`--exclude-dir=.git`:

| Tree | Files | Reading |
|---|---|---|
| `ethereum/go-ethereum` (current) | 0 implementations | ethash removed 2023; the one substring hit is a data blob |
| `ethereum/go-ethereum-pow` @ `v1.10.26` | 5 | `consensus/ethash/{algorithm,consensus,sealer}.go` + test |
| `ethereumclassic/core-geth` @ `4185df450` (2025-01-23) | 5 | same layout — **never gutted, because Ethereum Classic still runs proof of work** |
| `besu-eth/besu` (current) | 0 implementations | the one substring hit is a changelog line |
| `besu-eth/besu-etc` @ `eb4248c99` | 4 | `EthHash.java`, `PoWHasher.java`, test, changelog |
| `NethermindEth/nethermind` @ `c35ce1b1a` | 3 | `Nethermind.Consensus.Ethash/Ethash.cs` + benchmark + test |
| `erigontech/erigon` @ `7125aa1e8` | 3 | `execution/protocol/rules/ethash/` |

---

## What the mechanism needs from a keyspace

**Nothing, in the sense that binds a consensus-correctness requirement — the
cleanest such answer of any mechanism surveyed across this repository's
protocols.** `.claude/agents/forge.md` § "A consensus mechanism owns its own
namespace" states the rule this instantiates: a cache and a dataset are
derived artifacts, keyed by epoch, and every client treats retaining them as
a node's own memory-and-disk policy rather than as consensus state.
go-ethereum-pow LRUs them on the `Ethash` struct and memory-maps datasets to
files outside the chain database; nethermind holds a `_cacheCache` on its
`Ethash` class; besu-etc's `hashimotoLight` simply takes `int[] cache` as an
argument. **A node that lost every cache recomputes them and reaches
identical verdicts.**

**Check the other half before reading that as free**, which
`.claude/agents/forge.md`'s same section warns about: ethash's cost is memory
and CPU, not storage — a full dataset is on the order of a gigabyte at the
first epoch and grows by roughly 8 MB per epoch. A storage layer owes this
mechanism nothing; a resource-budgeting layer owes it real memory and real
CPU time.

## Retention

**Not applicable to the keyspace — there is nothing there to retain.** A
different, non-consensus question sits beside it: clients commonly persist a
derived cache and dataset as a pure performance optimization, entirely
outside any chain database and entirely reconstructable from the header
seed. That is a resource-management choice a client makes, never a
correctness requirement a storage layer must honor, and it should not be
confused with the retention declarations Clique and AuRa require.

---

## ECIP-1099 / Etchash: one parameter, and a seed convention that is easy to lose

**The proposal renames nothing about the algorithm and changes one
constant.** *"Ethash transitions to a modified Dagger Hashimoto algorithm,
referred to hereby as Etchash"* — ECIP-1099 @ `6edea7d05` (2021-02-01),
`status: Final`. **MEASURED**, and worth stating plainly: **no client names a
package, type, or production class `etchash` / `EtcHash`.** core-geth's
`consensus/` holds `beacon, clique, ethash, lyra2, misc` and nothing named
`etchash`; the only occurrences of the word in either tree are a core-geth
test function (`TestEtchash_11700000`) and a besu-etc test class
(`EtcHashTest`), neither a production symbol. besu-etc instead expresses the
change as a second implementation of a two-member `EpochCalculator`
interface — `DefaultEpochCalculator` and `Ecip1099EpochCalculator`, the
latter doubling both the epoch length and the divisor used to find it. The
name is the specification's alone; treat "Etchash" as a label for the
parameter change, not as a second engine to build.

**What changes: `oldEpochLength = 30000` becomes `newEpochLength = 60000` at
a per-network `ETCHASH_FORK_BLOCK`.** MEASURED, against the proposal and both
clients. Activation is per network and stated by the proposal, never
defaulted in code: ETC mainnet `11,700,000` (epoch 390), Mordor `2,520,000`
(epoch 84), Kotti *"no upgrade is required."* core-geth's own
`TestCalcEpochLength` independently writes `2520000` commented `// mordor`.

### The property that is easy to get wrong and hard to notice

**MEASURED, from the proposal and both clients.**

> **Under ECIP-1099 the epoch NUMBER halves, but the SEED is still counted in
> LEGACY epochs.** ECIP-1099 epoch `e` uses the seed of legacy epoch `2e`.

The proposal states the rule and the reason, which neither client's code
does: *"To avoid re-use of seeds oldEpochLength will continue to be used
within the seedHash function"*, and in its own reference pseudocode, *"keep
using oldEpochLength here so seeds don't overlap."*

- **core-geth**: `seedHash(epoch, epochLength)` derives `calcEpochBlock =
  epoch*epochLength + 1`, then divides that block by the base
  `epochLengthDefault`, not by the epoch's own length.
- **besu-etc**: `Ecip1099EpochCalculator.epochStartBlock` derives a start
  block from `cacheEpoch(block) * (EPOCH_LENGTH * 2) + 1`, and `cacheEpoch`
  itself divides by `EPOCH_LENGTH * 2` — the doubled divisor sizes the cache
  and dataset; the seed chain in both clients still walks in units of the
  base `EPOCH_LENGTH`.

**So two different divisors are in play at once after the fork: the epoch's
own length sizes the cache and the dataset, the legacy length counts the
seed.** An implementation using one divisor throughout is wrong in exactly
one of the two places and still produces well-formed bytes — this is caught
only by a test written for the fork specifically, never by a test that never
crosses it.

**The `+1` in `epoch * length + 1` is inert, and only because the lengths are
commensurate.** MEASURED: dividing `e*L + 1` by 30000 gives the same count as
dividing `e*L`, because both 30000 and 60000 are exact multiples of 30000. A
future length that was not a multiple would part them.

### Two further findings

**The alignment constraint is a recommendation in the specification and a
demand in core-geth's own comment.** The proposal: *"For the smoothest
possible transition activation should occur on a block in which an epoch
transition to an even epoch number is occurring,"* with a worked table (388
good, 389 bad, 390 good — ETC mainnet's own activation height, 11,700,000, is
epoch 390). core-geth's source states it as an equality check its own
comment calls a demand. **READ** — the difference in force is worth
carrying: one is guidance, the other an artifact of one client's equality
test, and neither should be read as stronger than it is without checking the
other.

**The epoch index moves BACKWARDS at the transition.** core-geth's source
records handling *"the ECIP1099 case where the next epoch is expected to be
LESSER THAN that of the previous state's future epoch number."* Confirmed
directly in `consensus/ethash/ethash.go`. **Any cache, LRU, or map keyed on
epoch number must tolerate a non-monotonic key across this one fork block.**
**READ**, first surfaced by an earlier, broader survey pass in this
project's own research and re-confirmed here directly against core-geth's
source.

### Boundary with ECIP-1017 — do not reconcile the two conventions

**Epoch and era use different boundary conventions.** ECIP-1099's epoch is
plain floor division, `block / epochLength`. ECIP-1017's era is
`N+1`-based — *"Era 1 (blocks 1 - 5,000,000)."* **MEASURED**, both documents.
Carrying one convention onto the other is wrong by one block per boundary.

---

## What the mechanism needs from other layers

### From cryptography: Keccak-512, which is not what the EVM hashes with

**MEASURED.** Ethash uses two digests, not one: **Keccak-256** for the seal
digest over the header, the seed chain, and the final result; **Keccak-512**
for every cache row, and twice per dataset item. This is not a new
cryptographic primitive to source — BouncyCastle's `KeccakDigest` covers both
widths — but it is a primitive nothing else in a typical EVM client reaches
for: everywhere else a client hashes with the 256-bit variant, ethash alone
needs the 512-bit one. **Volume, so the cost is not a surprise:** a
first-epoch cache is on the order of a million Keccak-512 invocations; a
first-epoch dataset is roughly thirty times that again.

### From the block header: the encoding minus the seal, and nothing else

**MEASURED.** The seal digest is `keccak256(rlp(header without mixHash and
nonce))` — the mandatory pre-merge fields, plus the fee-market field where
present. go-ethereum-pow's `SealHash` and besu-etc's `hashHeader` enumerate
exactly that list and both stop at the fee-market field.

**A defect worth not reproducing: besu-etc writes that field list out
twice** — `EthHash.hashHeader` and
`headervalidationrules/ProofOfWorkValidationRule.hashHeader` — two
transcriptions of one field order, the shape a real defect takes even where
neither copy is presently wrong. Write it once, in a place both the sealer
and the validator call, rather than reproducing the duplication.

**A property with real security content, and it is easy to lose:** the
difficulty is inside the preimage. A miner therefore cannot restate the work
its own nonce was done for — raising a sealed header's difficulty moves the
seal hash, so the mixed hash stops matching.

### From storage: nothing — see "What the mechanism needs from a keyspace" above

### From the fork schedule: nothing at all

**MEASURED.** No fork selects the seal algorithm; ethash and Etchash are one
algorithm throughout. The nearest thing in the corpus is besu-etc's
`ClassicProtocolSpecs.thanosDefinition` swapping the header validator at a
fork to one carrying an ECIP-1099 epoch calculator — the **parameter** is
fork-selected, the **algorithm** is not.

---

## The published test tier is two cases at one epoch

**MEASURED.** `PoWTests/ethash_tests.json` is one file, two cases, and the
file is byte-identical across the three corpora that carry it —
`ethereum/tests`, `etclabscore/tests`, `etclabscore/tests-etc` — so the
Ethereum Classic corpora add no case of their own. Both cases sit at epoch 0.
The tier is therefore blind to the seed chain iterating at all, to the size
search moving off its initial value, to every epoch boundary, and to
ECIP-1099 in every respect — the proposal postdates the fixture.

**What redeems it: each case states six intermediates, not just an
answer** — `seed`, `cache_size`, `full_size`, `header_hash` (the seal hash),
`cache_hash`, plus `mixHash`, `result` and the sealed header RLP. Two cases
checked at eight points is a far better instrument than two checked at one.

**`cache_hash` has no consumer in the corpus.** nethermind binds it in a test
type and never asserts it; nothing else reads the field. It agrees with
`keccak256` of the cache bytes — a derivation of the field's meaning by
agreement, not a reading of a source that states it. **READ.**

**Byte-exact cache and dataset expectations exist in exactly one place in
the corpus**: go-ethereum-pow's own cache- and dataset-generation tests
(epochs 0 and 1) and its light-equals-full mixing vector. The published
fixture tiers state neither artifact's contents.

**Nobody in the corpus builds a full real-epoch dataset inside a test
suite.** `execution-specs` marks its heavy ethash tests slow and gates
dataset generation on an external `geth` binary; besu-etc's own test builds
a real cache at a real epoch but only the light path, never the full
dataset; go-ethereum-pow's dataset test runs at a toy size, with real
generation living behind its `makedag` command.

---

## What was not surveyed

**One family, and this is the falsifier a claim from this file needs
named**, per `.claude/rules/reference-first.md` § "And one question about the
SURVEY, which none of the three asks": every client read here is an Ethereum
or Ethereum Classic client.
No other ethash-adjacent network or fork of the algorithm was opened, so a
claim from this file is a claim about that one family, never about proof of
work generally.

**Depth also varies by client, and it is worth stating rather than implying
uniform coverage.** The specification, go-ethereum-pow, core-geth, and
besu-etc were read for semantic divergence. nethermind and erigon were
opened only far enough to locate and count the seal implementation (the "5.
Where the seal lives" table); nothing here claims to have surveyed their
divergence. `openethereum/openethereum` was opened only far enough to
confirm it ships `ethash/src/progpow.rs` beside its ethash implementation —
see below — and was not otherwise read.

- **Mining.** `getWork`/`submitWork`, the sealer loop, remote-miner
  plumbing, and the disk formats a client persists caches and datasets in.
  Read only far enough to establish which side of the light/full split each
  call sits on.
- **ProgPoW.** `openethereum/openethereum` ships `ethash/src/progpow.rs`, and
  `ethereumclassic/core-geth` ships `consensus/lyra2` beside its ethash
  package. Neither was opened further. Their existence is the reason an
  unprefixed proof-of-work name would claim a namespace this mechanism does
  not have to itself.
- **Header validation beyond the seal.** Fork dispatch, difficulty
  targeting, and every other header rule are outside this file — see
  `.claude/protocols/consensus-pow.md` for the family's own domain facts.
- **`lambdaclass/ethrex` and `paradigmxyz/reth`.** Post-merge clients; not
  opened. A bound on breadth, not a finding.
- **The Olympia work trees** (`<work-root>/{core-geth,besu,nethermind}` in
  `.claude/reference-corpus.md`). Nothing here speaks to Olympia-era
  behavior.
- **ECIP-1043**, which ECIP-1099's own frontmatter records itself as
  replacing. Not opened.

---

## Evidence weight

**One pass, calibrated, with two executable reproductions behind the
algorithm's bytes — and no second-party audit yet, of any of it.** That is a
different profile from the four protocols beside this one, not a strictly
weaker or stronger one: they carry a partial second read with no independent
executable proof behind the mechanism itself; this file carries the reverse.
Re-derive a claim before treating it as settled, the same instruction
`.claude/protocols/consensus-clique.md` gives its own un-audited majority.

**`.claude/agents/forge.md` § Provenance still governs every value above.**
This file is evidence about the field, cited to the specification and to
production clients — never the authority for a byte, and never confirmed by
what `modules/consensus-pow` happens to already do, per "This mechanism is
already built" above.
