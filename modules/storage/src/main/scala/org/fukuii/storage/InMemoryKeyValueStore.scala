package org.fukuii.storage

import org.fukuii.bytes.Bytes

import scala.collection.mutable

/** The Phase 1 in-memory implementation of [[KeyValueStore]].
  *
  * ==Test and development only==
  *
  * This implementation has no write-ahead log, no flush, and no crash
  * recovery: its contents exist only in the process that created them. It
  * satisfies [[KeyValueStore]]'s contract fully and is correct for what it
  * is for — Phase 1 has no persistent backend, and nothing here is blocked
  * on one — but it must never be pointed at a live network. A production
  * store is a separate, separately-justified decision, gated on adopting a
  * key-value engine.
  *
  * ==What is deferred==
  *
  * Nothing here validates [[Layout]] against a prior open, because an
  * in-memory instance has no prior open to validate against — see [[Layout]].
  * Nothing here enforces a [[RetentionVector]], because nothing in Phase 1
  * prunes. Nothing here diffs or layers versions: `updateAt` for a version
  * that already holds entries adds to what is there, and reading a version
  * that was never written returns absence, exactly as an unversioned
  * namespace would for a key never written. Reconstructing one version from
  * another is Phase 3+ machinery this module does not build. Nothing here
  * validates that two [[Namespace]] values sharing a [[NamespaceId]] also
  * agree on [[Seam]] and [[WriteMode]] — identity is by id alone, the same
  * deferral Phase 1 already makes for [[Layout]] validation.
  *
  * ==Concurrency==
  *
  * Not thread-safe. A single instance is not meant to be shared across
  * threads.
  */
final class InMemoryKeyValueStore(val layout: Layout) extends KeyValueStore:

  private val unversioned: mutable.Map[NamespaceId, mutable.Map[Bytes, Bytes]] = mutable.Map.empty
  private val versioned: mutable.Map[(NamespaceId, Version), mutable.Map[Bytes, Bytes]] = mutable.Map.empty
  private var open: Boolean = true

  private def requireOpen(): Unit =
    if !open then throw new IllegalStateException("store is closed")

  private def keyspaceOf(id: NamespaceId): mutable.Map[Bytes, Bytes] =
    unversioned.getOrElseUpdate(id, mutable.Map.empty)

  private def keyspaceAt(id: NamespaceId, version: Version): mutable.Map[Bytes, Bytes] =
    versioned.getOrElseUpdate((id, version), mutable.Map.empty)

  private def applyBatch(
      target: mutable.Map[Bytes, Bytes],
      removals: Iterable[Bytes],
      upserts: Iterable[(Bytes, Bytes)]
  ): Unit =
    removals.foreach(target.remove)
    upserts.foreach((key, value) => target.update(key, value))

  /** Unsigned byte-lexicographic order, with the shorter of two agreeing
    * prefixes sorting first. `modules/storage` orders whatever bytes it is
    * given; see [[LeafIterator]] for why this is hash order for a secured
    * trie's keys without this module knowing what "secured" means.
    */
  private def lessThan(a: Bytes, b: Bytes): Boolean =
    val ai = a.toIArray
    val bi = b.toIArray
    val length = math.min(ai.length, bi.length)
    var i = 0
    var cmp = 0
    while i < length && cmp == 0 do
      cmp = (ai(i) & 0xff) - (bi(i) & 0xff)
      i += 1
    if cmp != 0 then cmp < 0 else ai.length < bi.length

  def get(namespace: Namespace, key: Bytes): Option[Bytes] =
    requireOpen()
    unversioned.get(namespace.id).flatMap(_.get(key))

  def getAt(namespace: Namespace, version: Version, key: Bytes): Option[Bytes] =
    requireOpen()
    versioned.get((namespace.id, version)).flatMap(_.get(key))

  def update(namespace: Namespace.Standalone, removals: Iterable[Bytes], upserts: Iterable[(Bytes, Bytes)]): Unit =
    requireOpen()
    applyBatch(keyspaceOf(namespace.id), removals, upserts)

  def updateAt(
      namespace: Namespace.Standalone,
      version: Version,
      removals: Iterable[Bytes],
      upserts: Iterable[(Bytes, Bytes)]
  ): Unit =
    requireOpen()
    applyBatch(keyspaceAt(namespace.id, version), removals, upserts)

  def admit(namespace: Namespace.Coupled, key: Bytes, value: Bytes, companionValue: Bytes): Unit =
    requireOpen()
    keyspaceOf(namespace.id).update(key, value)
    keyspaceOf(namespace.companion.id).update(key, companionValue)

  def leaves(namespace: Namespace, version: Version): LeafIterator =
    requireOpen()
    val snapshot = versioned.get((namespace.id, version)).map(_.toVector).getOrElse(Vector.empty)
    val ordered = snapshot.sortWith((x, y) => lessThan(x._1, y._1))

    final class Impl(entries: Vector[(Bytes, Bytes)]) extends LeafIterator:
      private var position: Int = 0
      private var closedFlag: Boolean = false

      def hasNext: Boolean = position < entries.length

      def next(): (Bytes, Bytes) =
        if !hasNext then throw new NoSuchElementException("next on an exhausted LeafIterator")
        val entry = entries(position)
        position += 1
        entry

      def isClosed: Boolean = closedFlag

      def close(): Unit = closedFlag = true

    new Impl(ordered)

  def clear(namespace: Namespace): Unit =
    requireOpen()
    val _ = unversioned.remove(namespace.id)
    val staleVersions = versioned.keys.filter((id, _) => id == namespace.id).toVector
    staleVersions.foreach(versioned.remove)

  def close(): Unit =
    if open then
      unversioned.clear()
      versioned.clear()
      open = false
