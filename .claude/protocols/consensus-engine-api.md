# Consensus — the Engine API boundary

**provenance:** every fact below is drawn from this build's own driver
seam — `modules/consensus-pos`'s `EngineDriver`, `EngineVersion`,
`EngineForkGate`, `EngineUpgrade`, `PayloadId`, `PayloadStatus`,
`ForkchoiceState` and `SafeAndFinalizedTracker` — which itself cites the
specification (`ethereum/execution-apis @ 6570b55`) and, where cited, a
production client at a ref that cannot move. This file consolidates what
those types already state and cite; it does not perform a fresh survey of the
specification or the clients. Every ref below was independently re-resolved
against this project's reference corpus, 2026-09-11, rather than trusted on
the strength of appearing in already-merged code.

**No frontmatter, and none is possible.** A file under `.claude/protocols/`
does not auto-load — Claude Code discovers `.claude/rules/`, not this
directory — so `paths:` here would do nothing. Something has to reach this
file by name, whether that is an import, a pointer a reader follows, or a
task brief quoting it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns the Engine API
boundary — the payload and fork-choice types, the driver verbs, the fork gate
that selects a version, the payload-to-block translation, and the safe and
finalized tracking — and instructs its reader to open the protocol for the
consensus-layer seam before acting on anything Engine-API-shaped. That
charter body loads on dispatch; this file does not. **So an Engine-API task
that never opened this protocol is running on recollection**, and the
charter treats that as the same finding as the protocol being absent.

**This is neither a family nor a mechanism protocol in the sense the other
files in this directory use those words, and it is read alongside whichever
of them a task is also in.** A family protocol is keyed to a network family;
a mechanism protocol is keyed to a consensus algorithm surveyed from
production clients. This seam is keyed to neither — it is the boundary
between an execution client and whatever consensus layer drives it, and it is
**family-neutral by construction**: `.claude/agents/forge.md` records
`diega/etc-cl` as a proof-of-work consensus layer that drives a post-Merge
execution client across this same Engine API, because modern execution
clients dropped total-difficulty tracking and difficulty-based fork choice.
Treat this protocol as in scope for a proof-of-work task that happens to
touch the driver seam, not only for a proof-of-stake one.

---

## Three independent version axes, not one "engine version"

**`engine_newPayload`, `engine_forkchoiceUpdated` and `engine_getPayload`
move version on three separate schedules, and from Prague onward a single
"engine version for this fork" is wrong.** At Prague, `newPayload` moves to
V4 while `forkchoiceUpdated` stays at V3; at Osaka, `getPayload` moves to V5
while the other two stand still. `EngineVersion.scala` models this as three
enums — `NewPayloadVersion` (V1–V5), `ForkchoiceUpdatedVersion` (V1–V4) and
`GetPayloadVersion` (V1–V6) — each carrying its own `ForkWindow`, rather than
as one integer threaded through all three verbs: a single `version: Int`
parameter is transposable at every call site and compiles regardless; three
distinct types are not.

**Two of the three axes are measurable from the published fixtures, and the
third is invisible in them — this is a corpus blind spot, not a coincidence,
and it matters because a design derived from the fixtures alone would ship a
defect the fixtures cannot show.** `blockchain_tests_engine` at
`tests-v20.0.1` publishes `newPayloadVersion` and `forkchoiceUpdatedVersion`
per payload and publishes **no `getPayload` version at all** — measured over
67,025 entries (`grep -rc '"newPayloadVersion"'` and the same for
`forkchoiceUpdatedVersion` agree exactly; `"getPayloadVersion"` returns zero),
where the two it does publish run `(1,1)`, `(2,2)`, `(3,3)` and then `(4,3)`
for Prague, Osaka and every blob-parameter-only label. So a
harness or a design that reads only what the fixtures assert about version
would find two axes and never notice it needs a third.

**The bounds themselves come from the specification's own fork documents,
each moving one axis at a time**, per `ethereum/execution-apis @ 6570b55`:
Cancun bounds all three V2s (`src/engine/cancun.md:235`); Prague bounds
`newPayloadV3` and `getPayloadV3` and extends `forkchoiceUpdatedV3` to cover
*"the Cancun **or Prague** forks"* (`prague.md:96,99`); Osaka bounds
`getPayloadV4` alone (`osaka.md:174`); Amsterdam bounds what is left
(`amsterdam.md:288`). **One bound in the specification's own text is wrong
read literally**: `prague.md:49` requires `newPayloadV4` to refuse a payload
whose timestamp *"does not fall within the time frame of the Prague fork,"*
which read literally would refuse every Osaka payload too. It cannot mean
that — Osaka's document supersedes `getPayloadV4` and says nothing about
`newPayloadV4`, and the published fixtures under `for_osaka` carry
`newPayloadVersion` 4. **The operative upper bound is the next upgrade that
actually moves the verb**, not the next upgrade of any kind.
`besu-eth/besu @ b330564a94` resolves it the same way — its
`VersionScheduler.thenFrom` closes a version's window at the upgrade that
opens the next one
(`ethereum/api/.../ExecutionEngineJsonRpcMethods.java:288-289`) — and is the
second source these bounds rest on.

**Where this build departs from besu, deliberately: an unconfigured version
stays callable here rather than disappearing.** besu drops a version from
its method registry entirely when the upgrade opening its window is not
configured on the network (`ExecutionEngineJsonRpcMethods.java:297-305`), so
an unconfigured version there answers *method not found*. This build has no
registry to drop a method from, so the version stays callable and the fork
gate refuses it with a named cause instead — dropping a method is a registry
decision, and refusing is the honest domain answer where no registry exists.
Mapping either outcome to a wire response is the transport's job, not this
seam's.

---

## A payload version is a strict-prefix chain, not a set of independent options

**`engine_newPayloadV3` and later take the base payload plus arguments the
specification adds together, and modeling those additions as independent
optional fields admits states the specification does not define.** Three
independently-optional fields — a parent beacon root, expected blob
versioned hashes, and execution requests — would admit **eight** combinations
by simple arithmetic; the specification defines exactly **three**: none of
them, the first two together, or all three together. A payload carrying
execution requests but no parent beacon root belongs to no version and can be
sent by no consensus layer.

**`NewPayloadRequest` is built as a chain rather than as three optionals for
this reason.** `BlobAndBeaconArguments` bundles `expectedBlobVersionedHashes`
and `parentBeaconBlockRoot` into one link, because the specification adds
them in one step (Cancun/`engine_newPayloadV3`) and no version takes one
without the other; `ExecutionRequestsArgument` nests inside it as the next
link (Prague/`engine_newPayloadV4`), rather than sitting beside it as a
fourth independent field. **The two arguments inside the first link are not
consumed equally, and the asymmetry is recorded rather than hidden**: the
parent beacon root becomes a header field, so a wrong one changes the block
hash and is caught by the same check every payload goes through; the expected
blob versioned hashes reach no header field at all, so **nothing about the
block hash can back them up** and they are checked directly, against the
versioned hashes inside the payload's own blob transactions.

**That check was absent for as long as nothing here could decode a blob
transaction, and its absence was recorded rather than hidden.** It was closed
when the decoder arrived and a fork registering blob transactions made the gap
reachable — and closing it changed what the published corpus does on this seam,
which is the evidence that it was a real gap rather than a formality: payloads
the corpus was built to reject had been deriving a matching header. **The check
runs before the hash check**, which is where the surveyed client puts it, and it
is gated on the argument's presence rather than run unconditionally — a
deliberate and recorded divergence from that client, which passes a null in its
place at the versions that carry none.

---

## Fork windows are half-open intervals, and must be — never sets of fork identities

**Each version's `ForkWindow` is `[firstSupported, firstUnsupported)`: served
at or after the lower bound, strictly before the upper.** Both ends are
independently optional, for real reasons rather than as a default: an absent
lower bound means the version has served since the Engine API began — true
of the earliest two versions of every verb, since before the upgrade that
introduced the second one there was no way to call either; an absent upper
bound means nothing has yet superseded the version. `besu-eth/besu @
b330564a94`'s `ForkSupportHelper.validateForkSupported` runs the identical
comparison, `<` on the lower bound and `>=` on the upper
(`ethereum/api/.../ForkSupportHelper.java:32,61`), and is the second source
this shape rests on — it is the only surveyed client that states the map as
data rather than as branches.

**An interval is the guard, not merely the representation, and a
blob-parameter-only upgrade is the case that shows why.** A BPO upgrade moves
only the blob schedule and touches no engine-method version the Engine API's
own documents key on, so BPO1 through BPO5 are deliberately absent from the
`EngineUpgrade` enum and are still served correctly — the window that covers
the fork before them covers them too, because an interval needs no edit to
admit a point it already contains. A **set** of fork identities would need
editing for every BPO upgrade that ships, and an omission would silently
refuse live traffic on a payload the network considers ordinary. This is the
entire payoff of the interval representation, and it is the reason to reject
any redesign that turns this into a per-fork membership list, however
tempting that looks for readability.

**The refusal a mismatch produces names what it compared, because a bare
"unsupported fork" sends the caller back to the schedule with no way to tell
which bound fired or against what.** `ForkGateRefusal`'s cases —
before-first-supported, at-or-after-first-unsupported, upgrade-not-scheduled,
upgrade-refused-by-network, upgrade-not-on-the-timestamp-axis, and
structure-not-modeled — are six distinguishable domain facts, not one
"-38005" collapsed into a boolean. The specification spells every one of them
the same wire number (`ethereum/execution-apis @ 6570b55`
`src/engine/common.md:101`), and attaching that number is the transport's
step; distinguishing the six is this seam's.

---

## A fork gate is resolved per call, never cached

**A production client shipped exactly the incident a cached fork gate
invites, and the fix was to populate the map, not to stop caching — read the
root cause precisely, because the wrong lesson is "never cache."**
`besu-eth/besu @ b330564a94`
`ethereum/core/.../DefaultProtocolSchedule.java:57-63` documents a copy
constructor that left its milestone map empty, so every lookup answered
absent for every fork — and, verbatim: *"Engine API handlers (e.g.
EngineForkchoiceUpdatedV3) cache those Optionals at construction time and
then reject every payload at the Cancun/Amsterdam boundary with
UNSUPPORTED_FORK."* What was cached was not wrong; it was **incomplete at
the moment it was taken**, and caching froze a transient emptiness into a
permanent refusal.

**This build resolves every comparison inside the call for exactly that
reason, as defense in depth rather than as a repair.** The schedule this
build resolves against is immutable and can only be built through a
constructor that validates it, so there is no window today in which a lookup
would be incomplete, and caching would currently be safe. Resolving per call
is what keeps it safe if the schedule ever becomes lazily populated — which
is precisely the change that produced besu's incident, made in a place that
had no idea it was load-bearing. **A future change that memoizes a fork
gate's lookups is a regression against this fact, whatever its own
justification, unless it also proves the schedule it reads can never be
incomplete at construction.**

---

## `PayloadId` is a locally-minted opaque handle, never a protocol value

**The specification types the identifier `DATA`, 8 bytes, and states nothing
about its content beyond uniqueness**: it is minted by `forkchoiceUpdated`
when a build begins and handed back to `getPayload` to name that build —
`ethereum/execution-apis @ 6570b55` `src/engine/paris.md:142`, *"Every new
build process MUST be uniquely identified by the returned `payloadId`
value."*

**The two clients read for it derive it two incompatible ways, which is why
nothing on this seam interprets it.** `ethereum/go-ethereum @ 02872e9ef`
`beacon/engine/types.go:201` declares an 8-byte array and reads its first
byte as a private version tag (`:204-206`). `besu-eth/besu @ b330564a94`
`consensus/merge/.../PayloadIdentifier.java:28` holds a `UInt64` instead,
derived by folding the build parameters together with shifts and
exclusive-ors (`:56-93`). Adopting either derivation would be adopting one
client's private encoding as though it were the protocol, so this seam
carries the eight bytes and offers no reading of them — the only shape both
clients' identifiers fit into.

**It is fixed-width for the same reason a block nonce is.** `DATA` is a byte
string whose leading zeros are part of it, where `QUANTITY` drops them. An
identifier held as a number and rendered as a quantity would shorten whenever
its first byte happened to be zero, and the consensus layer would hand back
a shorter string than the one it was actually issued.

---

## Only `forkchoiceUpdated` moves the head

**The specification and every surveyed production client keep "validate and
offer a payload" and "name the canonical head" as two separate operations,
and only the second moves anything a caller would call the chain's head.**
`engine_newPayload` offers a payload for validation; the fork gate and
structure check run before any of it executes, matching the specification's
own step order (`ethereum/execution-apis @ 6570b55` `src/engine/cancun.md:
111-113`). `engine_forkchoiceUpdated` names the canonical head and,
optionally, asks for a block to be built — the only one of the two whose job
is to say which chain is canonical.

**Every surveyed client treats "move the head" as the ordinary
canonical-chain operation, a materially larger thing than tracking safe and
finalized.** `ethereum/go-ethereum @ 02872e9ef` adds exactly two setters
beside its existing canonical-chain operation — `SetFinalized`
(`core/blockchain.go:800`) and `SetSafe` (`:812`) — where moving the head is
`SetCanonical` (`:2814`), which takes a lock, may re-execute a reorged chain,
and recovers ancestors. `besu-eth/besu @ b330564a94` declares
`setFinalized`/`getFinalized` and `setSafeBlock`/`getSafeBlock`
(`consensus/merge/.../MergeContext.java:135-156`) and has **no head accessor
at all** on that type. So a head move is not new state introduced by this
boundary; it is the pre-existing canonical-chain operation, driven by
`forkchoiceUpdated`.

**This build has no chain, no canonical head and no block store to move
today** — `SafeAndFinalizedTracker` holds only the two newer facts a
consensus layer names besides the head, and says so plainly: naming it a
"head, safe and finalized tracker" would overstate what it holds by a third
and would invite a chain layer to be built behind an interface that never
asked for one. **The shape above is a fact about the specification and the
field, not a claim about what this build currently does** — but the two
driver methods already keep it distinct at the type level: `newPayload`
never returns anything resembling a head change, and `forkchoiceUpdated`
alone answers with a status about the named head. A chain layer built later
must preserve that split; folding "insert" and "become canonical" into one
call site the first time a real chain exists would be the same defect that
motivates this section.

**One invariant the specification states and this seam cannot itself
check:** `ForkchoiceState.safeBlockHash` **must** be `headBlockHash` or one
of its ancestors (`ethereum/execution-apis @ 6570b55` `src/engine/paris.md:65`),
and the same requirement reaches `finalizedBlockHash`. Ancestry is a
question about a chain, and `ForkchoiceState` is three hashes with no chain
attached — so the check is stated here, on the type's contract, precisely so
it is not invented once per call site or skipped at one of them, and it
becomes whoever holds the chain's obligation to actually run it.

---

## `engine_getBlobs` is not this seam's, and the fork ladder does not decide it

**All six surveyed consensus-layer clients name `engine_newPayload`,
`engine_forkchoiceUpdated`, `engine_getPayload` and `engine_getBlobs`
equally** — swept across `Consensys/teku`, `sigp/lighthouse`,
`prysmaticlabs/prysm`, `status-im/nimbus-eth2`, `ChainSafe/lodestar` and
`grandinetech/grandine`, main sources only, calibrated against a verb that
does not exist returning nothing. So a fourth verb is exactly as universal
across the calling side as the three this seam models, and "this trait models
what a consensus layer calls" would be a false description of the calling
side. `engine_getBlobs` is excluded on **ownership**, not on readiness, and
three separate facts settle it — the fork ladder is not one of them.

**It is not gated by the fork ladder this seam otherwise organizes around,
and the specification says so in its own words — do not reason about this
boundary from the fork schedule at all.** `engine_getBlobsV1` sits in the
Cancun document and is annotated *"This is a new method introduced after
Cancun. It is defined here because it is backwards-compatible with
Cancun"* (`ethereum/execution-apis @ 6570b55` `src/engine/cancun.md:187`).
`V2` and `V3` are introduced by Osaka's document (`src/engine/osaka.md:94,122`),
so a case can be made for treating those two as not yet relevant to a build
that has not scheduled Osaka — but `V1` is not on that document at all, and
reaching Cancun neither brings `engine_getBlobsV1` into range nor leaves it
out of range, because nothing about a fork boundary decides it either way. Reasoning from "this is a post-Cancun addition, so it
is out of scope until later" reaches the wrong conclusion for exactly the
version most likely to be asked for first.

**What it answers is pool state, which belongs to whoever owns mempool
policy, never to this seam.** It serves blobs out of the transaction pool,
and the specification leaves what is in that pool to the client:
*"execution layer clients may prune old blobs from their pool"*
(`src/engine/cancun.md:211`), and client software **MAY** *"return an array
of all `null` entries if syncing or otherwise unable to serve blob pool
data"* (`:209`). A policy that moves no state root and is tunable without a
hard fork is `banksy`'s by the litmus in `consensus-change.md` — and the
surface it would be offered over (the method name, the version, the
ordering requirement on the response array, the `-38004: Too large request`
code, the one-second timeout) is a JSON-RPC namespace, which is `conduit`'s.
**Neither is this seam**, which owns what a fork selects and what order a
driver may call in, and this verb touches neither.

**It could not be correctly typed here regardless.** Its response is
`BlobAndProofV1`, a 131,072-byte SSZ blob beside a 48-byte KZG proof
(`src/engine/cancun.md:77-78`). This fork brought blob gas and the
commitments' versioned hashes; it did not bring a blob —
`org.fukuii.types.Transaction.Blob`'s own field states that blobs travel
beside the transaction on the network layer and are not part of it here —
and no transaction pool is modeled anywhere in this build. A method whose
correct answer requires a value this build has never populated cannot be
offered honestly by this seam even where ownership were otherwise unclear.

---

## Refusals here are domain values; a wire number is the transport's to attach

**Nothing on this seam mentions a transport.** The Engine API is carried
over authenticated HTTP in production, but authentication, framing and JSON
are properties of the carrier, not of the contract — a driver that took them
as arguments could not be exercised without standing one up. So every
refusal this seam produces (`NewPayloadRefusal`, `ForkchoiceUpdatedRefusal`,
`GetPayloadRefusal`, and `ForkGateRefusal` beneath them) is a domain value,
and each specification citation attached to one names the wire number a
transport will send — `-38005` for a fork refusal, `-32602` for a malformed
structure, `-38003` for invalid payload attributes, `-38001` for an unknown
payload identifier. **Attaching the number is `conduit`'s step, because
`conduit` owns the JSON-RPC namespace this verb is exposed over**; deciding
which of the distinguishable domain causes applies is this seam's, because
that decision needs the fork schedule, the version ladder and the payload
identifier's own bookkeeping, none of which the transport holds.

---

## This mechanism is already built, and describing it commits this project to nothing further

**Unlike a mechanism protocol written ahead of a network on the roadmap,
this one describes a seam this build has already implemented.**
`modules/consensus-pos` carries tracked sources for every type this file
names — `EngineDriver`, the three version enums, `EngineForkGate`,
`EngineUpgrade`, `PayloadId`, `PayloadStatus`, `ForkchoiceState`,
`SafeAndFinalizedTracker`, `PayloadAttributes`, `PayloadFactory`,
`PayloadStore` and `PayloadTranslation` — so "this file commits fukuii to
implementing nothing" is not a claim this file can make. What this file
commits to is narrower and still real: the four architectural facts above —
three independent axes, a strict-prefix chain rather than independent
options, half-open intervals rather than a fork set, and a locally-minted
identifier this build never interprets — are what a future change to this
seam must not foreclose, and `engine_getBlobs` staying outside it is a
boundary a future change must not quietly cross by adding a pool-serving
method here because it looks adjacent.

---

## Evidence weight

**Every architectural claim above traces to a type this build has already
shipped, each citing the specification or a production client at a ref that
does not move.** This file's own contribution is consolidation and
independent re-resolution of those refs, not a fresh reading of the
specification or the six consensus-layer clients from scratch — where this
file states a fact drawn from a client the driver seam's own code did not
already cite (none, by design), that would need marking as a new claim
rather than a restated one. Treat a disagreement between this file and the
driver seam's own scaladoc as the driver seam being current and this file
needing a re-check, not the reverse: the code is the built artifact, and this
protocol exists to make the code's own reasoning reachable without opening
thirteen files, not to become a second copy that outlives what it describes.
