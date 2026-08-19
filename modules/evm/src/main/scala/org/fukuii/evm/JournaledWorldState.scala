package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}

import scala.collection.mutable

/** A [[WorldState]] that holds its writes back, so that they can be dropped.
  *
  * ==An invocation that fails leaves no trace, and that needs somewhere to
  * undo==
  *
  * At this fork an exceptional halt undoes the state its invocation wrote. The
  * specification does it by copying the pending writes before an invocation
  * runs and putting the copy back when it ends in error, and both of its entry
  * points do so -- the create path and the ordinary message path alike, so the
  * outermost invocation of a transaction is covered by the same mechanism as a
  * nested one. go-ethereum reaches the same place through a journal it rewinds
  * to a marked point.
  *
  * That is why this is not waiting on nested invocations to exist: a frame that
  * halts has to be undone whether or not anything called it, and a seam whose
  * writes had already reached the trie would have nothing left to undo.
  *
  * ==What this covers, and what widens it==
  *
  * Storage writes, which are the only writes the operations built against this
  * seam can make. Balance, nonce and code writes arrive with value transfer and
  * contract creation, and each is a field on this journal rather than a change
  * to its shape.
  *
  * ==Nothing here takes the snapshot==
  *
  * [[snapshot]] and [[restore]] are the mechanism; deciding when an invocation
  * begins and whether it ended in error belongs to whatever drives invocations,
  * which does not exist yet. Until it does, a caller running a frame that can
  * halt is what stands between a failed invocation and a state root that
  * commits to its writes.
  */
final class JournaledWorldState(base: WorldState) extends WorldState:

  private val pending: mutable.LinkedHashMap[(Address, Word), Word] = mutable.LinkedHashMap.empty

  def balanceOf(address: Address): Word = base.balanceOf(address)

  def codeOf(address: Address): Bytes = base.codeOf(address)

  def storageAt(address: Address, slot: Word): Word =
    pending.getOrElse((address, slot), base.storageAt(address, slot))

  def setStorage(address: Address, slot: Word, value: Word): Unit =
    pending((address, slot)) = value

  /** What this has written so far, in a form [[restore]] can put back. */
  def snapshot(): JournaledWorldState.Snapshot =
    new JournaledWorldState.Snapshot(pending.toMap)

  /** Returns the held writes to what they were when `taken` was made.
    *
    * Writes made since are discarded rather than reversed, which is what makes
    * this correct for a partly-run invocation: a value read back after a
    * restore is the one the failed invocation never saw.
    */
  def restore(taken: JournaledWorldState.Snapshot): Unit =
    pending.clear()
    pending ++= taken.writes

  /** Passes every held write down to the state beneath and stops holding them.
    *
    * A zero is passed down as a zero, because the layer below is where a zero
    * becomes an absence -- see [[StateTrieWorldState]].
    *
    * The order writes are passed in is not a property anything below depends
    * on: a trie commits to the mapping it ends up holding and not to the order
    * it was built in.
    */
  def commit(): Unit =
    pending.foreach((slot, value) => base.setStorage(slot._1, slot._2, value))
    pending.clear()

object JournaledWorldState:

  /** The held writes at one moment, readable only by the journal that made it.
    *
    * A caller holds one and hands it back; it cannot read a write out of it,
    * which keeps a snapshot from becoming a second way to ask what storage
    * holds.
    */
  final class Snapshot private[evm] (private[evm] val writes: Map[(Address, Word), Word])
