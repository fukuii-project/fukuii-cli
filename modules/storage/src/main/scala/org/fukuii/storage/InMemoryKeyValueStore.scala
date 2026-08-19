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
  * deferral Phase 1 already makes for [[Layout]] validation. One narrower
  * agreement is not deferred — see below.
  *
  * ==The shape registry==
  *
  * A [[NamespaceId]] is classified — [[Namespace.Standalone]], or
  * [[Namespace.Coupled]] naming a specific companion id — the first time any
  * operation is given a [[Namespace]] carrying it, and every later operation
  * naming the same id must agree or the call throws
  * `IllegalArgumentException` rather than proceeding.
  *
  * This is the run-time half of [[KeyValueStore]]'s admission invariant.
  * [[KeyValueStore]]'s signatures alone stop a [[Namespace.Coupled]] value
  * from being passed to `update` or `updateAt`, but they cannot stop a
  * *different* [[Namespace.Standalone]] value that merely carries the same
  * id as an already-[[Namespace.Coupled]] namespace — that value
  * type-checks as an ordinary `update` argument, and absent this check would
  * write into the coupled namespace's own keyspace with no companion value
  * ever supplied. See [[KeyValueStore]]'s admission-invariant documentation
  * for the requirement this satisfies.
  *
  * ==Concurrency==
  *
  * Not thread-safe. A single instance is not meant to be shared across
  * threads.
  */
final class InMemoryKeyValueStore(val layout: Layout) extends KeyValueStore:

  /** A [[NamespaceId]]'s classification, as recorded by [[registerShape]]. */
  private enum Shape:
    case AsStandalone
    case AsCoupled(companionId: NamespaceId)

  private def shapeOf(namespace: Namespace): Shape = namespace match
    case Namespace.Standalone(_, _, _)         => Shape.AsStandalone
    case Namespace.Coupled(_, _, _, companion) => Shape.AsCoupled(companion.id)

  private val unversioned: mutable.Map[NamespaceId, mutable.Map[Bytes, Bytes]] = mutable.Map.empty
  private val versioned: mutable.Map[(NamespaceId, Version), mutable.Map[Bytes, Bytes]] = mutable.Map.empty
  private val shapes: mutable.Map[NamespaceId, Shape] = mutable.Map.empty
  private var open: Boolean = true

  private def requireOpen(): Unit =
    if !open then throw new IllegalStateException("store is closed")

  /** Throws unless `namespace`'s id is either unclassified or already
    * classified the way `namespace` itself would classify it. Never mutates
    * the registry — see [[registerShape]] for the commit half, kept separate
    * so [[KeyValueStore.admit]] can check both its namespace and its
    * companion before either is recorded, and reject the call without
    * recording either half of a conflicting pair.
    */
  private def requireShapeAgreement(namespace: Namespace): Unit =
    val incoming = shapeOf(namespace)
    shapes.get(namespace.id).foreach { registered =>
      if registered != incoming then
        throw new IllegalArgumentException(
          s"namespace '${namespace.id.label}' was first seen as ${registered.toString}, cannot now be used as ${incoming.toString}"
        )
    }

  /** Classifies `namespace`'s id on first use; on every later use, requires
    * agreement with what was first recorded. Every operation below checks
    * every namespace it was given before recording any of them, so a
    * conflict is reported before any write happens rather than after a
    * partial one — see [[KeyValueStore.admit]]'s own atomicity requirement.
    */
  private def registerShape(namespace: Namespace): Unit =
    requireShapeAgreement(namespace)
    shapes.update(namespace.id, shapeOf(namespace))

  private def keyspaceOf(id: NamespaceId): mutable.Map[Bytes, Bytes] =
    unversioned.getOrElseUpdate(id, mutable.Map.empty)

  private def keyspaceAt(id: NamespaceId, version: Version): mutable.Map[Bytes, Bytes] =
    versioned.getOrElseUpdate((id, version), mutable.Map.empty)

  private def applyBatch(
      target: mutable.Map[Bytes, Bytes],
      removals: Iterable[Bytes],
      upserts: Iterable[(Bytes, Bytes)]
  ): Unit =
    // Both arguments are drawn before `target` is touched, because the batch is
    // atomic for any `Iterable` and not only for a strict one: a lazy argument
    // that throws partway would otherwise leave the map holding some of the
    // batch, which is the state this operation promises never to produce.
    val toRemove = removals.toVector
    val toUpsert = upserts.toVector
    toRemove.foreach(target.remove)
    toUpsert.foreach((key, value) => target.update(key, value))

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
    registerShape(namespace)
    unversioned.get(namespace.id).flatMap(_.get(key))

  def getAt(namespace: Namespace, version: Version, key: Bytes): Option[Bytes] =
    requireOpen()
    registerShape(namespace)
    versioned.get((namespace.id, version)).flatMap(_.get(key))

  def update(namespace: Namespace.Standalone, removals: Iterable[Bytes], upserts: Iterable[(Bytes, Bytes)]): Unit =
    requireOpen()
    registerShape(namespace)
    applyBatch(keyspaceOf(namespace.id), removals, upserts)

  def updateAt(
      namespace: Namespace.Standalone,
      version: Version,
      removals: Iterable[Bytes],
      upserts: Iterable[(Bytes, Bytes)]
  ): Unit =
    requireOpen()
    registerShape(namespace)
    applyBatch(keyspaceAt(namespace.id, version), removals, upserts)

  def admit(namespace: Namespace.Coupled, key: Bytes, value: Bytes, companionValue: Bytes): Unit =
    requireOpen()
    require(
      namespace.companion.id != namespace.id,
      "a namespace coupled to itself has no companion to be admitted against"
    )
    requireShapeAgreement(namespace)
    requireShapeAgreement(namespace.companion)
    registerShape(namespace)
    registerShape(namespace.companion)
    keyspaceOf(namespace.id).update(key, value)
    keyspaceOf(namespace.companion.id).update(key, companionValue)

  def leaves(namespace: Namespace, version: Version): LeafIterator =
    requireOpen()
    registerShape(namespace)
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
    registerShape(namespace)
    val _ = unversioned.remove(namespace.id)
    val staleVersions = versioned.keys.filter((id, _) => id == namespace.id).toVector
    staleVersions.foreach(versioned.remove)

  def close(): Unit =
    if open then
      unversioned.clear()
      versioned.clear()
      shapes.clear()
      open = false
