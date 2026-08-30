package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt64}

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
  * ==What is held back, and what a snapshot has to cover==
  *
  * Every write the seam admits: storage, balance, nonce, code, and the bare
  * existence an account gains by being touched. A snapshot that covered only
  * some of them would restore an invocation to a state it never occupied --
  * which is worse than not restoring at all, because it would still look
  * consistent.
  *
  * ==The machine's environment names this type rather than [[WorldState]]==
  *
  * Not an oversight: every invocation of this fork must be undoable, so a view
  * with no way to undo cannot run one. The base beneath is any `WorldState`, so
  * what varies -- a trie, a test double, a view at an earlier block -- varies
  * where it should.
  */
final class JournaledWorldState(base: WorldState) extends WorldState:

  private val storage: mutable.LinkedHashMap[(Address, Word), Word] = mutable.LinkedHashMap.empty
  private val balances: mutable.LinkedHashMap[Address, Word] = mutable.LinkedHashMap.empty
  private val nonces: mutable.LinkedHashMap[Address, UInt64] = mutable.LinkedHashMap.empty
  private val codes: mutable.LinkedHashMap[Address, Bytes] = mutable.LinkedHashMap.empty
  private val brought: mutable.LinkedHashSet[Address] = mutable.LinkedHashSet.empty

  def balanceOf(address: Address): Word = balances.getOrElse(address, base.balanceOf(address))

  def nonceOf(address: Address): UInt64 = nonces.getOrElse(address, base.nonceOf(address))

  def codeOf(address: Address): Bytes = codes.getOrElse(address, base.codeOf(address))

  def accountExists(address: Address): Boolean = brought.contains(address) || base.accountExists(address)

  /** Any pending write to `address`'s storage answers true, whatever it wrote.
    *
    * That includes a pending zero, and it is the specification's own reading:
    * its `account_has_storage` tests the address's pending map for emptiness
    * and never the values in it.
    */
  def hasStorage(address: Address): Boolean =
    storage.keysIterator.exists(_._1 == address) || base.hasStorage(address)

  def storageAt(address: Address, slot: Word): Word =
    storage.getOrElse((address, slot), base.storageAt(address, slot))

  /** Delegated rather than read off [[base]] directly.
    *
    * `base.storageAt` would be the transaction-start value only while this is
    * the outermost journal; `base.committedStorageAt` is right however many
    * layers sit below. Nothing nests journals today -- `BlockProcessor` builds
    * one per transaction and nested frames snapshot on it -- so the two agree
    * at present, which is exactly why writing the fragile one would never
    * fail.
    */
  def committedStorageAt(address: Address, slot: Word): Word =
    base.committedStorageAt(address, slot)

  def setStorage(address: Address, slot: Word, value: Word): Unit =
    storage((address, slot)) = value

  def setBalance(address: Address, value: Word): Unit =
    bringIntoBeing(address)
    balances(address) = value

  def setNonce(address: Address, value: UInt64): Unit =
    bringIntoBeing(address)
    nonces(address) = value

  def setCode(address: Address, code: Bytes): Unit =
    bringIntoBeing(address)
    codes(address) = code

  def touch(address: Address): Unit = bringIntoBeing(address)

  private def bringIntoBeing(address: Address): Unit =
    if !base.accountExists(address) then
      val _ = brought.add(address)

  /** What this has written so far, in a form [[restore]] can put back. */
  def snapshot(): JournaledWorldState.Snapshot =
    new JournaledWorldState.Snapshot(
      storage.toMap,
      balances.toMap,
      nonces.toMap,
      codes.toMap,
      brought.toSet
    )

  /** Returns the held writes to what they were when `taken` was made.
    *
    * Writes made since are discarded rather than reversed, which is what makes
    * this correct for a partly-run invocation: a value read back after a
    * restore is the one the failed invocation never saw.
    */
  def restore(taken: JournaledWorldState.Snapshot): Unit =
    storage.clear()
    val _ = storage ++= taken.storage
    balances.clear()
    val _ = balances ++= taken.balances
    nonces.clear()
    val _ = nonces ++= taken.nonces
    codes.clear()
    val _ = codes ++= taken.codes
    brought.clear()
    val _ = brought ++= taken.brought

  /** Passes every held write down to the state beneath and stops holding them.
    *
    * A zero is passed down as a zero, because the layer below is where a zero
    * becomes an absence -- see [[StateTrieWorldState]].
    *
    * ==The order of these loops does NOT matter, and this paragraph used to say
    * it did==
    *
    * It claimed storage must go first, because a two-level implementation reads
    * an account's storage root as it writes the account. **The real invariant is
    * one layer down and stronger**: [[org.fukuii.trie.StateTrie.putAccount]]
    * never accepts nor trusts a caller-supplied storage root -- it re-derives
    * from the live storage trie on every call -- and
    * [[StateTrieWorldState.setStorage]] rewrites the account leaf
    * unconditionally on both its branches. So every loop below is
    * read-current-then-write-one-field, and any order converges on the same leaf.
    *
    * **The field is stronger still, and settles it.** The executable
    * specification's `Account` has no storage-root field at all -- only `nonce`,
    * `balance` and `code_hash` -- and injects the root at encode time through a
    * `get_storage_root` callback, so its own account and storage diffs are
    * applied in whatever order a dict iterates. It reaches the same
    * order-independence by never storing the value rather than by always
    * re-deriving it.
    *
    * **Protecting a property that does not exist is worse than not protecting
    * it**, because it points the next reader away from the one that does.
    *
    * Within each kind the order is not a property anything below depends on: a
    * trie commits to the mapping it ends up holding and not to the order it was
    * built in.
    */
  def commit(): Unit =
    storage.foreach((slot, value) => base.setStorage(slot._1, slot._2, value))
    brought.foreach(base.touch)
    codes.foreach((address, code) => base.setCode(address, code))
    nonces.foreach((address, value) => base.setNonce(address, value))
    balances.foreach((address, value) => base.setBalance(address, value))
    storage.clear()
    balances.clear()
    nonces.clear()
    codes.clear()
    brought.clear()

object JournaledWorldState:

  /** The held writes at one moment, readable only by the journal that made it.
    *
    * A caller holds one and hands it back; it cannot read a write out of it,
    * which keeps a snapshot from becoming a second way to ask what storage
    * holds.
    */
  final class Snapshot private[evm] (
      private[evm] val storage: Map[(Address, Word), Word],
      private[evm] val balances: Map[Address, Word],
      private[evm] val nonces: Map[Address, UInt64],
      private[evm] val codes: Map[Address, Bytes],
      private[evm] val brought: Set[Address]
  )
