package org.fukuii.consensus.pos

import org.fukuii.bytes.Hash

/** The two blocks a consensus layer names besides the head, held for whoever
  * asks.
  *
  * ==Two, not three, and the name says so on purpose==
  *
  * Neither surveyed client tracks the head here. `ethereum/go-ethereum` @
  * `02872e9ef` adds exactly two setters beside an existing canonical-chain
  * operation — `SetFinalized` at `core/blockchain.go:800` and `SetSafe` at
  * `:812` — where moving the head is `SetCanonical` at `:2814`, a far larger
  * thing that takes a lock, may re-execute a reorged chain and recovers
  * ancestors. `besu-eth/besu` @ `b330564a94` declares
  * `setFinalized`/`getFinalized` and `setSafeBlock`/`getSafeBlock` on
  * `consensus/merge/.../MergeContext.java:135-156` and no head accessor at all.
  *
  * So the head move is the ordinary canonical-chain operation and only these
  * two are new state. Calling this a head, safe and finalized tracker would
  * overstate it by a third and would invite a chain layer to be built behind an
  * interface that never asked for one.
  *
  * ==A port, because the thing that will implement it does not exist==
  *
  * There is no chain, no canonical head and no block store anywhere in this
  * build. What that costs is stated below under the invariant this cannot
  * check; what it means for the shape is that the seam is the deliverable and
  * the in-memory class behind it is a deferral, not a stand-in.
  *
  * That is this project's established shape for exactly this situation:
  * `org.fukuii.storage.InMemoryKeyValueStore` sits beside its own trait one
  * layer down, and a mechanism module here already defers what needs a chain
  * rather than inventing one — a header validator takes its parent as an
  * argument because the caller has usually discharged it already.
  *
  * ==Hashes, not headers, and that is the chain's absence showing==
  *
  * Both clients store a `BlockHeader`. Neither is handed one: the Engine API
  * gives three hashes (`ethereum/execution-apis` @ `6570b55`
  * `src/engine/paris.md:64-66`), and each client resolves a hash to a header
  * through its own chain before storing it. **With no chain there is nothing to
  * resolve against**, so this holds what it was actually given. A port that
  * promised headers would be promising a lookup nothing here can perform.
  *
  * ==The invariant this CANNOT check, and a client with a chain does not check
  * it either==
  *
  * [[ForkchoiceState]] carries the specification's requirement that the safe
  * block be the head or one of its ancestors. Ancestry is a question about a
  * chain and this holds hashes, so nothing here can answer it.
  *
  * **The tempting move is to let the caller be trusted for it, and the calling
  * side is where that fails.** Two of the three consensus-layer clients read
  * for this uphold the ancestry by construction: each resolves the justified
  * node by root and then walks descendants from it, so a head is a descendant
  * of the justified block rather than merely compared against it —
  * `Consensys/teku` @ `488a89357e`
  * `storage/.../protoarray/ProtoArray.java:297-311`, taking the justified
  * node's best descendant, and `sigp/lighthouse` @ `e423a66763`
  * `consensus/proto_array/src/proto_array.rs:1081-1090` resolving
  * `justified_root` and walking from that index. The third does not.
  *
  * `prysmaticlabs/prysm` @ `8512330b35` takes its safe hash from
  * `beacon-chain/blockchain/service.go:467-478`, which falls through to
  * `UnrealizedJustifiedPayloadBlockHash()` — a checkpoint the store raises
  * whenever any inserted block computes a higher unrealized justified epoch
  * (`beacon-chain/forkchoice/doubly-linked-tree/unrealized_justification.go:88-92`,
  * `if uj.Epoch > s.unrealizedJustifiedCheckpoint.Epoch`). That is a maximum
  * over everything inserted, with no test that the result is an ancestor of the
  * head being reported or on the canonical chain at all. The branch above it
  * that would confirm a root runs only when a feature flag is set
  * (`config/features/config.go:282-285`, guarded by `ctx.IsSet`), so it is off
  * unless an operator asks for it.
  *
  * **So an execution layer that trusted the caller would be trusting a property
  * two lineages of three supply.** The requirement therefore stays where the
  * specification puts it — on the verb, once something here can resolve a hash
  * — and this type checks nothing about it, because a hash carries neither a
  * parent nor a height.
  *
  * ==Durability is asymmetric, and this port promises neither half==
  *
  * `ethereum/go-ethereum` @ `02872e9ef` persists the finalized hash and does
  * not persist the safe one — `SetFinalized` calls
  * `rawdb.WriteFinalizedBlockHash` (`core/blockchain.go:803`) and `SetSafe`
  * writes nothing, there being no `WriteSafeBlockHash` in that package at all.
  * Its own comment says what follows: *"the safe block is not stored on disk
  * and it is set to the last known finalized block on startup"*
  * (`core/blockchain.go:681-682`).
  *
  * **That asymmetry is a property of an implementation, not of this seam**, so
  * it is recorded here and required of nobody. An implementation that persists
  * is free to; one that does not is free not to. What an implementation must
  * NOT do is persist the safe hash and claim the pair recovers together, since
  * the field's own recovery rule rebuilds safe from finalized rather than from
  * its own record.
  */
trait SafeAndFinalizedTracker:

  /** What the consensus layer last named as finalized, or nothing if it has
    * named none.
    *
    * ==Absent and zero are different answers==
    *
    * The protocol's way of saying nothing is finalized yet is a hash of zeros —
    * both fields *"are allowed to have `0x0000...0000` value unless transition
    * block is finalized"* (`ethereum/execution-apis` @ `6570b55`
    * `src/engine/paris.md:68`) — which is a thing the consensus layer said.
    * `None` here is the different fact that it has said nothing at all, which
    * is every node's state before its first forkchoice call.
    *
    * Collapsing the two would make a node that has never been told
    * indistinguishable from one told there is nothing yet, and only the first
    * of those is a reason to wait.
    */
  def finalized: Option[Hash]

  /** What the consensus layer last named as safe, under the same reading. */
  def safe: Option[Hash]

  /** Record what a forkchoice call named.
    *
    * Both at once rather than one setter each, because a forkchoice call
    * carries both and recording one without the other leaves the pair
    * describing two different calls. Neither client needs that guard — each
    * sets them from one handler — and neither offers a reason to split them.
    */
  def record(finalized: Hash, safe: Hash): Unit

object SafeAndFinalizedTracker:

  /** Thirty-two zero bytes: the protocol's way of naming no block.
    *
    * Derived rather than written out, for the reason
    * [[PayloadTranslation.EmptyOmmersHash]] is derived.
    */
  val NoBlock: Hash = Hash.fromBytesTruncating(IArray.empty)

  extension (tracker: SafeAndFinalizedTracker)

    /** Whether a real block has been finalized, as opposed to never told or
      * told there is none.
      *
      * The two negative answers are kept apart on the reader above and folded
      * together here, because a caller asking this wants one boolean and the
      * distinction it loses is one it has already been offered.
      */
    def hasFinalizedBlock: Boolean = tracker.finalized.exists(_ != NoBlock)

    def hasSafeBlock: Boolean = tracker.safe.exists(_ != NoBlock)

/** The tracker with nothing behind it.
  *
  * ==What this is for==
  *
  * Exercising the seam, and running a node that has no store to put this in.
  * It is the deferral the port's own documentation describes, and it is
  * replaced rather than extended once something owns chain state.
  *
  * ==Not thread-safe, deliberately, and both clients are==
  *
  * `ethereum/go-ethereum` @ `02872e9ef` holds each value in an
  * `atomic.Pointer` (`core/blockchain.go:357-358`) and `besu-eth/besu` @
  * `b330564a94` holds each in an `AtomicReference`
  * (`PostMergeContext.java:58-59`), because the Engine API is served
  * concurrently in both.
  *
  * This one is not, matching the convention
  * `org.fukuii.storage.InMemoryKeyValueStore` already sets for an in-memory
  * implementation in this build. **That is a property of this class and not of
  * the port**, and an implementation serving real traffic has to supply what
  * the two clients supply.
  */
final class InMemorySafeAndFinalizedTracker extends SafeAndFinalizedTracker:

  private var lastFinalized: Option[Hash] = None
  private var lastSafe: Option[Hash] = None

  def finalized: Option[Hash] = lastFinalized

  def safe: Option[Hash] = lastSafe

  def record(finalized: Hash, safe: Hash): Unit =
    lastFinalized = Some(finalized)
    lastSafe = Some(safe)
