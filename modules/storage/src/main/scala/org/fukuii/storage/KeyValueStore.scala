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
  * [[Namespace.Standalone]], so a write to a coupled namespace's primary
  * keyspace with no value for its companion does not merely fail at run
  * time — there is no way to write the call at all. This generalizes the
  * requirement that a chain header must never enter the chain-header store
  * without its total difficulty into a namespace-pairing mechanism this
  * module can express without knowing what a header or a total difficulty
  * is; which namespaces need the pairing is a decision for whatever layer
  * constructs [[Namespace.Coupled]] values.
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
