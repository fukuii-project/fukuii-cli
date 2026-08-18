package org.fukuii.storage

/** A short label identifying a keyspace within a [[KeyValueStore]].
  *
  * An opaque wrapper over `String` rather than a bare one, so a namespace
  * identifier cannot be passed where an unrelated `String` is expected, and
  * the reverse. `modules/storage` assigns no meaning to the label — which
  * namespace holds which kind of data is a decision for whatever layer
  * declares [[Namespace]] values, never for this module.
  */
opaque type NamespaceId = String

object NamespaceId:
  def apply(label: String): NamespaceId = label
  extension (id: NamespaceId) def label: String = id

/** The first namespace axis: which of L2's two seams a namespace belongs to.
  *
  * Chain data (headers, total difficulty, bodies, receipts) and state (trie
  * nodes, accounts, storage) are retained under independent policies — a
  * proof-of-work network keeps chain data back to genesis while pruning
  * state aggressively. The axis exists to make that expressible rather than
  * assumed; `modules/storage` does not itself apply a retention policy to
  * either seam.
  */
enum Seam:
  case ChainData, State

/** The second namespace axis, orthogonal to [[Seam]]: whether a namespace's
  * entries may be overwritten in place, or may only ever be appended.
  *
  * An append-only namespace's table set is a one-way door once real data has
  * been written: a set fixed at first write cannot acquire a new member
  * later without migrating everything already stored. `modules/storage` does
  * not itself enforce append-only-ness in Phase 1 — there is no persistent
  * backend for the property to matter against yet — but the axis exists so a
  * namespace's classification is a recorded, checkable fact from the first
  * store constructed rather than a convention nothing captures.
  */
enum WriteMode:
  case Mutable, AppendOnly

/** A keyspace within a [[KeyValueStore]], tagged along both namespace axes.
  *
  * [[Namespace]] carries no knowledge of what is stored in it — not a node,
  * an account, or any other trie-shaped concept, and not a name like "chain
  * header" or "total difficulty". Those are decisions for whatever layer
  * constructs [[Namespace]] values; this type exists so that layer can
  * declare a keyspace's shape once and have every operation over it agree.
  *
  * ==Standalone and Coupled==
  *
  * Most namespaces are [[Namespace.Standalone]] and are written through the
  * ordinary `update`/`updateAt` operations on [[KeyValueStore]].
  *
  * [[Namespace.Coupled]] exists for the one requirement stated as a
  * structural obligation rather than a convention: a namespace whose entries
  * must never be admitted without a value for a declared companion namespace,
  * in the same atomic write — the chain-header store must never hold a
  * header with no total difficulty, on the evidence `KeyValueStore.admit`
  * documents. `KeyValueStore.update` and `updateAt` accept only
  * [[Namespace.Standalone]], so there is no operation that writes to a
  * [[Namespace.Coupled]] namespace's own keyspace without also supplying its
  * companion's value in the same call — see [[KeyValueStore.admit]]. A
  * namespace pairing that needs this is declared `Coupled` by whoever
  * constructs it; `modules/storage` never decides which namespaces require
  * it.
  */
enum Namespace:
  case Standalone(id: NamespaceId, seam: Seam, writeMode: WriteMode)
  case Coupled(id: NamespaceId, seam: Seam, writeMode: WriteMode, companion: Namespace.Standalone)

object Namespace:

  extension (namespace: Namespace)

    def id: NamespaceId = namespace match
      case Standalone(id, _, _) => id
      case Coupled(id, _, _, _) => id

    def seam: Seam = namespace match
      case Standalone(_, seam, _) => seam
      case Coupled(_, seam, _, _) => seam

    def writeMode: WriteMode = namespace match
      case Standalone(_, _, writeMode) => writeMode
      case Coupled(_, _, writeMode, _) => writeMode
