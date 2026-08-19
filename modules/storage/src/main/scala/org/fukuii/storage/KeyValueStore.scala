package org.fukuii.storage

import org.fukuii.bytes.Bytes

/** The persistence contract every storage component in fukuii goes through.
  *
  * Four operations, in the same shape wherever this contract is implemented:
  * `get` returns a value or nothing, absence never being an error; `update`
  * applies removals and upserts together as one atomic batch, because
  * splitting them into two calls opens a window in which half a logical
  * write is visible; `clear` empties a namespace; `close` releases the store
  * and everything it holds.
  *
  * `modules/storage` is byte-pure: every key and value here is
  * [[org.fukuii.bytes.Bytes]], and this trait has no knowledge of node
  * shape, accounts, or the trie. Namespace separation along both of L2's
  * axes — see [[Seam]] and [[WriteMode]] — is the only structure this module
  * imposes; what a namespace's bytes mean is a decision for whatever layer
  * declares it, and this module depends on nothing that could tell it.
  *
  * ==Versioned operations==
  *
  * `getAt`, `updateAt` and `leaves` add a [[Version]] to the unversioned
  * operations above. They exist for the state seam's versioned key→value
  * view — the foundation `modules/trie`'s node-access seam computes a
  * commitment over. Nothing here restricts a [[Namespace]] tagged
  * [[Seam.ChainData]] from using them; the state seam needs them and the
  * chain-data seam has not been found to, which is a fact about the field's
  * own decomposition rather than a rule this trait enforces.
  *
  * ==The admission invariant==
  *
  * `admit` is the only operation that writes to a [[Namespace.Coupled]]
  * namespace's own keyspace, and it requires a value for the companion
  * namespace in the same call. `update` and `updateAt` accept only
  * [[Namespace.Standalone]], so a call passing the [[Namespace.Coupled]]
  * value itself does not compile. That much is a property of this trait's
  * own signatures, guaranteed for every implementation without any of them
  * writing a line of enforcement.
  *
  * It is not the whole invariant. [[Namespace]] identity is by
  * [[NamespaceId]] alone, so a *different* [[Namespace.Standalone]] value
  * that merely carries the same id as an already-admitted
  * [[Namespace.Coupled]] namespace type-checks as an ordinary `update`
  * argument — the signature above stops the [[Namespace.Coupled]] value,
  * not a same-id alias of it. An implementation that keys its storage by id
  * alone must reject that alias itself, at run time, or a header can enter
  * the chain-header store with no total difficulty ever written for it,
  * silently. **Every implementation of this trait MUST reject it**: a
  * [[NamespaceId]] is classified — [[Namespace.Standalone]], or
  * [[Namespace.Coupled]] naming a specific companion id — on the first
  * operation that names it, and a later operation naming the same id under
  * a different classification must fail rather than silently proceed. See
  * [[InMemoryKeyValueStore]]'s shape registry for the reference shape.
  *
  * Together these generalize the requirement that a chain header must never
  * enter the chain-header store without its total difficulty into a
  * namespace-pairing mechanism this module can express without knowing what
  * a header or a total difficulty is; which namespaces need the pairing is
  * a decision for whatever layer constructs [[Namespace.Coupled]] values.
  */
/** ==Ordering across seams, which no operation here can enforce==
  *
  * Chain data and state are separate seams and no operation spans them, so a
  * write touching both is two calls and nothing makes them one. Cross-seam
  * atomicity is deliberately not attempted — no surveyed client provides it
  * either — and what stands in for it is an ordering rule:
  *
  * '''state is durably committed before the chain data that commits to it.'''
  *
  * The two directions fail differently, which is what makes the rule
  * one-directional rather than a preference. Interrupted after state and before
  * the header, a node holds state nothing references: garbage, discardable, and
  * invisible to every reader. Interrupted the other way, it holds a header
  * claiming state the node does not have, which every later read believes.
  *
  * This is stated here because it binds a caller rather than an implementation,
  * and every write path built later — genesis, block import, any
  * checkpoint-anchored sync — adopts it separately or diverges silently.
  */
trait KeyValueStore:

  /** The representation this store was constructed with. See [[Layout]]. */
  def layout: Layout

  def get(namespace: Namespace, key: Bytes): Option[Bytes]

  def getAt(namespace: Namespace, version: Version, key: Bytes): Option[Bytes]

  /** Applies `removals` and `upserts` as one atomic batch. Where a key
    * appears in both, the upsert wins: the result is as if every removal ran
    * first and every upsert ran after, never the reverse.
    */
  def update(namespace: Namespace.Standalone, removals: Iterable[Bytes], upserts: Iterable[(Bytes, Bytes)]): Unit

  /** [[update]], scoped to a single [[Version]] of `namespace`. */
  def updateAt(
      namespace: Namespace.Standalone,
      version: Version,
      removals: Iterable[Bytes],
      upserts: Iterable[(Bytes, Bytes)]
  ): Unit

  /** Writes `value` under `key` in `namespace`'s own keyspace, and
    * `companionValue` under the same `key` in `namespace.companion`'s
    * keyspace, as one atomic batch. The only way to admit an entry into a
    * coupled namespace — see [[Namespace.Coupled]].
    *
    * A namespace coupled to itself is rejected. The pairing exists so that an
    * entry cannot be admitted without a value for its companion, and a
    * companion that is the namespace itself is not a value for anything: the
    * two writes would be the same keyspace and the same key, so the second
    * would overwrite the first and the obligation would be met by discarding
    * the thing it was protecting.
    */
  def admit(namespace: Namespace.Coupled, key: Bytes, value: Bytes, companionValue: Bytes): Unit

  /** An ordered, iterable view of `namespace` at `version`. The returned
    * [[LeafIterator]] holds a resource and MUST be closed on every path —
    * see [[LeafIterator]].
    */
  def leaves(namespace: Namespace, version: Version): LeafIterator

  /** Empties `namespace`: every version's entries, for a namespace read or
    * written through the versioned operations, and its unversioned entries.
    */
  def clear(namespace: Namespace): Unit

  /** Releases the store and everything it holds. Idempotent: calling it more
    * than once has no further effect. Every operation above throws
    * `IllegalStateException` once the store is closed, rather than silently
    * reporting an empty result — a closed store being read from is a caller
    * error, not a normal empty case.
    */
  def close(): Unit
