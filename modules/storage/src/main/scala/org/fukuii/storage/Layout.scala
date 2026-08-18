package org.fukuii.storage

/** An opaque label for a representation choice made above `modules/storage` —
  * for instance, which of state's addressable-node or no-node-store shape a
  * `modules/trie` implementation has committed to for a given store.
  *
  * `modules/storage` assigns no meaning to the label. It exists so a
  * [[Layout]] can be compared for equality without this module knowing what
  * the comparison means.
  */
opaque type RepresentationId = String

object RepresentationId:
  def apply(label: String): RepresentationId = label
  extension (id: RepresentationId) def label: String = id

/** The representation choice a store was constructed with, recorded once.
  *
  * Four independent clients converge on treating this choice as a stored
  * property rather than a re-readable setting: written at initialization,
  * and thereafter a disagreement is an error naming the stored value rather
  * than a silent switch. [[Layout]] also names which chain-data namespaces
  * the store was initialized with, because a fixed append-only table set is
  * the same class of one-way door as the representation choice itself — a
  * store built without an entry cannot acquire it later without migrating
  * everything already written.
  *
  * ==What Phase 1 delivers, and what it does not==
  *
  * Phase 1 has no persistent backend and therefore no "open" event to
  * validate against — an in-memory store is constructed once and discarded,
  * never reopened. [[Layout]] here is a typed field carried on construction;
  * the validate-on-open enforcement this decision otherwise requires is
  * deferred to the first persistent backend, which is the first
  * implementation with an open event to validate at.
  *
  * @param representation
  *   the opaque state-representation marker, meaningless to this module.
  * @param chainDataNamespaces
  *   the chain-data namespace identifiers this store was initialized to
  *   hold. Not validated against anything in Phase 1 — see above.
  */
final case class Layout(representation: RepresentationId, chainDataNamespaces: Set[NamespaceId])
