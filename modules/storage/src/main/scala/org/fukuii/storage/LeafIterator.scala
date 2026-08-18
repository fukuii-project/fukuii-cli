package org.fukuii.storage

import org.fukuii.bytes.Bytes

/** An open, ordered view over a [[Namespace]]'s entries at a [[Version]].
  *
  * Entries are returned in ascending byte order of the key. For a namespace
  * whose keys are already keccak-hashed before insertion — as a secured
  * trie's are — ascending byte order over the hashed key IS hash order, which
  * is the ordering the snap wire protocol's account and storage range
  * responses require. `modules/storage` does not hash keys itself; it orders
  * whatever bytes it is given, and hash order is a property of what the
  * caller wrote rather than something this module computes.
  *
  * ==Release==
  *
  * A [[LeafIterator]] is an `AutoCloseable` and MUST be closed on every
  * path, including a path that fails partway through iteration —
  * `scala.util.Using.resource` is the ordinary way to get that for free. The
  * reason is not the leaked object: an open iterator at a version is a pin
  * against that version's data being discarded, so a leaked iterator keeps
  * data alive that a future pruning implementation cannot reclaim, and the
  * symptom shows up as unexplained retention far from the leak. Phase 1's
  * in-memory implementation never discards data — nothing in Phase 1 prunes
  * — so the pin has no observable effect yet; the contract is stated now
  * because a rule added after the first implementation that prunes cannot
  * describe iterators that were already open when it lands.
  *
  * `close()` is idempotent: calling it more than once has no further effect.
  */
trait LeafIterator extends Iterator[(Bytes, Bytes)] with AutoCloseable:

  /** Whether [[close]] has already run. Exposed so a caller — ordinarily a
    * test — can confirm release happened on a path that does not simply run
    * to exhaustion.
    */
  def isClosed: Boolean
