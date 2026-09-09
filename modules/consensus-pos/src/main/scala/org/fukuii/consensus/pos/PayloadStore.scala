package org.fukuii.consensus.pos

/** The payloads a build process has produced, held until they are collected or
  * evicted.
  *
  * ==Bounded, because an unbounded one is a leak and neither client ships one==
  *
  * An identifier is minted on every build request the consensus layer makes,
  * which is one per slot for as long as the node runs, and nothing in the
  * protocol ever removes one: `engine_getPayload` collects a payload and the
  * specification says nothing about discarding it. So a map keyed on the
  * identifier grows without limit, and the growth is invisible because each
  * entry is small and correct.
  *
  * Both surveyed clients bound it, by different means.
  * `ethereum/go-ethereum` @ `02872e9ef` keeps a fixed array of ten and shifts
  * on insert, evicting the oldest — `maxTrackedPayloads = 10` at
  * `eth/catalyst/queue.go:31`, with `put` copying the array down by one at
  * `:68` — and its own comment says why the bound is small: *"Ideally we should
  * only ever track the latest one; but have a slight wiggle room for non-ideal
  * conditions"* (`:28-30`). `besu-eth/besu` @ `b330564a94` bounds it the other
  * way, keeping one best proposal per identifier and discarding the rest
  * (`PostMergeContext.java:241-302`).
  *
  * **This takes go-ethereum's shape**, because the eviction rule is a property
  * of the store where besu's is a property of a block producer that keeps
  * improving a proposal — and this build has no producer to improve one. The
  * choice is revisited when it does; see [[PayloadFactory]].
  *
  * ==Newest first, and the scan is linear on purpose==
  *
  * A consensus layer collects the payload it most recently asked for, so the
  * entry wanted is almost always the newest. At ten entries a scan is shorter
  * than a hash, and go-ethereum's queue scans the same way. The capacity is
  * what keeps that true, so raising it far is a change to more than a number.
  *
  * ==Not thread-safe==
  *
  * go-ethereum guards its queue with an `RWMutex` and besu synchronizes on the
  * collection, because both serve the Engine API concurrently. This does not,
  * matching the convention this build already sets for an in-memory structure
  * — see [[InMemorySafeAndFinalizedTracker]], which records the same departure
  * and the same obligation on whatever serves real traffic.
  *
  * @param capacity
  *   how many payloads to keep. go-ethereum's ten is the default and is a
  *   tuning value rather than anything the protocol states, which is why it is
  *   a parameter and not a constant.
  */
final class PayloadStore(val capacity: Int = PayloadStore.DefaultCapacity):

  require(capacity > 0, "a payload store that keeps nothing would refuse every collection")

  private var entries: Vector[(PayloadId, BuiltPayload)] = Vector.empty

  /** How many payloads are held, which is never more than [[capacity]]. */
  def size: Int = entries.size

  /** Store a payload under an identifier, evicting the oldest if full.
    *
    * ==Storing the same identifier twice replaces rather than duplicates==
    *
    * A build process improves its payload while it runs, so a second answer for
    * one identifier is a better version of the first rather than a second
    * build. Keeping both would leave [[get]] choosing between them, and the one
    * it must return is *"the most recent version of the payload that is
    * available"* (`ethereum/execution-apis` @ `6570b55`
    * `src/engine/paris.md:263`) — so the newer answer wins and the older is
    * dropped.
    *
    * **go-ethereum does not do this and does not need to**, because it stores a
    * handle that resolves the current best at collection time rather than a
    * finished payload (`eth/catalyst/queue.go:74-91`, `item.payload.Resolve()` at `:86`).
    * Storing finished payloads is what makes replacement this store's job.
    */
  def put(id: PayloadId, payload: BuiltPayload): Unit =
    val withoutId = entries.filterNot(_._1 == id)
    entries = ((id, payload) +: withoutId).take(capacity)

  /** The payload stored under an identifier, or nothing.
    *
    * Nothing is the answer both for an identifier no build ever produced and
    * for one whose payload has been evicted. The two are indistinguishable
    * here and the protocol treats them alike: `engine_getPayload` answers
    * `-38001: Unknown payload` where *"the build process identified by the
    * `payloadId` does not exist"* (`src/engine/paris.md:265`), which is what a
    * caller reports for either.
    */
  def get(id: PayloadId): Option[BuiltPayload] =
    entries.collectFirst { case (stored, payload) if stored == id => payload }

  /** Whether an identifier would be answered, without producing the payload. */
  def holds(id: PayloadId): Boolean = entries.exists(_._1 == id)

object PayloadStore:

  /** How many payloads a store keeps unless told otherwise.
    *
    * go-ethereum's `maxTrackedPayloads` (`eth/catalyst/queue.go:31`). Adopted
    * as a starting figure rather than as a protocol constant — the
    * specification states no bound, and the value is only defensible alongside
    * that client's reasoning that one would nearly do.
    */
  val DefaultCapacity: Int = 10
