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
  * ==Three kinds of member sit here, and they differ in TWO independent ways==
  *
  * The paragraph above is the rule for writes and is not the rule for this
  * type. Whether a member is put back by [[restore]] and whether it is carried
  * down by [[commit]] are separate questions, and each of the three answers
  * them differently:
  *
  *   - **The five writes above** -- restored, and committed. What a state is.
  *   - **[[transientStorageAt]]'s keyspace** -- restored, and NOT committed. A
  *     write EIP-1153 requires the transaction to discard.
  *   - **[[wasCreatedInTransaction]]'s record** -- NOT restored, and not
  *     committed. Not a write at all: a fact about what this transaction has
  *     done, which EIP-6780 requires to outlive the invocation that did it.
  *
  * **So a member being absent from [[snapshot]] is a decision this type makes
  * per member**, and the last row is the one where adding it would look like
  * tidying up.
  *
  * ==The machine's environment names this type rather than [[WorldState]]==
  *
  * Not an oversight: every invocation of this fork must be undoable, so a view
  * with no way to undo cannot run one. The base beneath is any `WorldState`, so
  * what varies -- a trie, a test double, a view at an earlier block -- varies
  * where it should.
  *
  * ==One kind of write is held here and never passed down==
  *
  * [[transientStorageAt]] and [[setTransientStorage]] answer a keyspace that
  * exists for the length of one transaction and reaches no account. It is held
  * beside the writes above because it needs the same undo and none of the
  * commit: EIP-1153 requires that *"if a frame reverts, all writes to transient
  * storage that took place between entry to the frame and the return are
  * reverted, including those that took place in inner calls"* and that *"all
  * values in transient storage are discarded at the end of the transaction"*
  * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-1153.md`, Final).
  *
  * **Both fall out of where it sits rather than from anything written for
  * them.** The first is [[snapshot]] and [[restore]], which `Interpreter.run`
  * already drives around every invocation. The second is this object's own
  * lifetime -- one is built per transaction -- and [[commit]] not carrying it
  * down.
  *
  * The executable specification puts it in exactly this place, as a member of
  * the same record as the pending writes: `ethereum/execution-specs` @
  * `0cc100eb1` `forks/cancun/state_tracker.py:76` declares it on
  * `TransactionState`, `copy_tx_state` at `:637` copies it beside the storage
  * writes, `restore_tx_state` at `:658` puts it back, and
  * `incorporate_tx_into_block` merges the other three into the block and at
  * `:690` clears this one instead.
  *
  * **The field disagrees about the home and not about the behavior.**
  * `ethereum/go-ethereum` @ `02872e9ef` puts it on the `core/vm` `StateDB`
  * interface; `besu-eth/besu` @ `b330564a9` holds an
  * `UndoTable<Address, Bytes32, Bytes32>` on `TxValues`, beside the frame stack,
  * undone by the same `undoChanges(mark)` that undoes its warm sets. Each is
  * that client's transaction-lifetime record, which is what this type already
  * is.
  */
final class JournaledWorldState(base: WorldState) extends WorldState:

  private val storage: mutable.LinkedHashMap[(Address, Word), Word] = mutable.LinkedHashMap.empty
  private val balances: mutable.LinkedHashMap[Address, Word] = mutable.LinkedHashMap.empty
  private val nonces: mutable.LinkedHashMap[Address, UInt64] = mutable.LinkedHashMap.empty
  private val codes: mutable.LinkedHashMap[Address, Bytes] = mutable.LinkedHashMap.empty
  private val brought: mutable.LinkedHashSet[Address] = mutable.LinkedHashSet.empty
  private val transient: mutable.LinkedHashMap[(Address, Word), Word] = mutable.LinkedHashMap.empty
  private val createdHere: mutable.LinkedHashSet[Address] = mutable.LinkedHashSet.empty

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

  /** What `address` holds at `slot` in the keyspace this transaction is
    * discarding, which is zero until something writes there.
    *
    * ==Which account owns the keyspace is the CALLER's answer, not this
    * type's==
    *
    * The proposal makes it the account the code is running AS -- *"when
    * transient storage is used in the context of `DELEGATECALL` or `CALLCODE`,
    * then the owning contract of the transient storage is the contract that
    * issued"* the operation, and the target under `CALL` or `STATICCALL`
    * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-1153.md`, Final) -- which is the
    * same account [[storageAt]] is asked for and is named at the one call site
    * that has it. Deriving it here would need a frame this type cannot see.
    *
    * ==It reads nothing beneath, which is what separates it from every other
    * read here==
    *
    * Every accessor above falls through to [[base]] where it holds nothing.
    * This one cannot: there is no committed transient storage anywhere for it
    * to fall through to, and a keyspace that answered from the state beneath
    * would be persistent storage under another name.
    */
  def transientStorageAt(address: Address, slot: Word): Word =
    transient.getOrElse((address, slot), Word.Zero)

  /** Writes `value` at `address`'s `slot` in that keyspace.
    *
    * A zero is written rather than erased, which the specification's own
    * `set_transient_storage` does the other way round. **The two are
    * indistinguishable**: a key holding zero and an absent key both answer zero
    * through [[transientStorageAt]], and nothing enumerates this map -- there is
    * no counterpart to [[hasStorage]], because no account's emptiness turns on
    * it. So the specification's erasure is a saving rather than a rule, and
    * neither form is a state root apart from the other.
    */
  def setTransientStorage(address: Address, slot: Word, value: Word): Unit =
    transient((address, slot)) = value

  /** Records that `address` was brought into existence by a creation this
    * transaction ran.
    *
    * ==This is the one record here a failed invocation does NOT take back==
    *
    * Every other member is in [[snapshot]] and is put back by [[restore]], so a
    * frame that reverts leaves nothing. This one is in neither, deliberately:
    * *"The parent reference and ``created_accounts`` are shared (not rolled
    * back)"* and *"The marker is not removed even if the account creation
    * reverts. Since the account cannot have had code prior to its creation and
    * can't call ``get_storage_original()``, this is harmless."*
    * (`ethereum/execution-specs` @ `0cc100eb1`,
    * `forks/cancun/state_tracker.py:615,425-427`). Its `copy_tx_state` passes
    * the set through by reference at `:636` where every other member is copied,
    * and its `restore_tx_state` puts back four members at `:655-658` and not
    * this one.
    *
    * **The second sentence is quoted through to its own verdict, and that is
    * what decides this site rather than the derivation below.** A quotation
    * stopped one sentence earlier reads as the specification stating a rule two
    * clients break, which is a different and worse claim than the one the text
    * supports.
    *
    * **What the specification states is that ITS choice is harmless; that the
    * opposite choice is equally harmless is one step past the quotation and is
    * marked as such.** It follows from the reason given rather than from a
    * second statement: the premise is that the marked account can never be
    * asked about, and an account that can never be asked about is one whose
    * marker is unobservable whichever way a client records it. So the two
    * clients below are exercising a latitude the specification's own reasoning
    * grants, rather than contradicting a rule it states.
    *
    * `org.fukuii.execution.SystemCall.GasPrice` states the tiebreak this build
    * applies to an unobservable divergence, and states it there because that is
    * the site where the OTHER answer was reached.
    *
    * **So the property is the absence of two lines rather than the presence of
    * any**, which is why it is stated here: nothing in [[snapshot]] or
    * [[restore]] mentions this map, and a reader who added it to either would
    * be making the change look like consistency.
    *
    * ==Two production clients roll it back, and the divergence is not
    * observable==
    *
    * `ethereum/go-ethereum` @ `02872e9ef` holds the marker as `newContract` on
    * its state object and journals the write, so `createContractChange.revert`
    * sets it false (`core/state/journal.go:480-482`, written by
    * `StateDB.CreateContract` at `core/state/statedb.go:677-682`).
    * `besu-eth/besu` @ `b330564a9` holds an `UndoSet<Address> creates` on
    * `TxValues` and undoes it in `undoChanges(mark)`
    * (`evm/.../frame/TxValues.java:55,159`), reached from `MessageFrame.rollback()`
    * at `:1505` and from `AbstractMessageProcessor` at `:138`, which both its
    * revert and its exceptional-halt paths call.
    *
    * **The divergence is DERIVED to be unobservable here as well as declared
    * so above, and the derivation is this**, stated so a reader can check the
    * specification's own verdict rather than take it: a creation
    * only reaches an address [[Interpreter.deployableAt]] admits, which is an
    * address holding no code; a creation that then fails is undone, so the
    * address holds no code afterwards either. Both readers of this marker -- a
    * destruction, and the committed value a store is priced against -- are
    * reached only by running code, and an address with none runs nothing. A
    * later creation at the same address records the marker again. So a marker
    * this build keeps and those two clients drop can only be asked about an
    * address that cannot ask.
    *
    * **A derivation is not a measurement, and the published corpus is the
    * measurement beside it.** `state_tests/for_cancun/cancun/eip6780_selfdestruct`
    * is filled for exactly this interaction -- `journal_revert`,
    * `reentrancy_selfdestruct_revert` and a `selfdestruct_revert` pair naming
    * the created and not-created cases separately -- and all 136 of its cases
    * agree under this build's reading, as do all 123 of
    * `cancun/eip1153_tstore`. That is corroboration and not proof: a corpus
    * filled from the specification cannot state an expectation for a behavior
    * the specification calls harmless, so what those 259 cases establish is that
    * the shape is exercised and nothing diverged, never that no input exists.
    * `CancunCompositionCertificationSpec` holds the run.
    */
  def markAccountCreated(address: Address): Unit =
    val _ = createdHere.add(address)

  /** Whether `address` was brought into existence by a creation this
    * transaction ran.
    *
    * Answers false for every address of a state this transaction found already
    * populated, which is the question EIP-6780 turns on: *"the current
    * behaviour is preserved when `SELFDESTRUCT` is called in the same
    * transaction a contract was created"* (`ethereum/EIPs` @ `d2a64c2d4`,
    * `EIPS/eip-6780.md`, Final).
    */
  def wasCreatedInTransaction(address: Address): Boolean = createdHere.contains(address)

  private def bringIntoBeing(address: Address): Unit =
    if !base.accountExists(address) then
      val _ = brought.add(address)

  /** What this has written so far, in a form [[restore]] can put back.
    *
    * **[[wasCreatedInTransaction]]'s record is deliberately not in here**, and
    * that member's own documentation carries the reason and the citation. A
    * snapshot holding it would be undone by every failed invocation, which is
    * the behavior EIP-6780 forbids.
    */
  def snapshot(): JournaledWorldState.Snapshot =
    new JournaledWorldState.Snapshot(
      storage.toMap,
      balances.toMap,
      nonces.toMap,
      codes.toMap,
      brought.toSet,
      transient.toMap
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
    transient.clear()
    val _ = transient ++= taken.transient

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
    *
    * ==TWO members are cleared and not carried down, which is the whole of what
    * "discarded" means==
    *
    * [[setTransientStorage]]'s writes reach no account and no trie, and
    * [[markAccountCreated]]'s record is not a write at all. The specification's
    * own `incorporate_tx_into_block` merges its three write maps into the block
    * and clears exactly these two instead (`ethereum/execution-specs` @
    * `0cc100eb1`, `forks/cancun/state_tracker.py:689-690`).
    *
    * **Neither clear is redundant with this object's lifetime, and that is why
    * both are written.** One journal is built per transaction today, so an
    * uncleared member would be collected with the journal and nothing would
    * observe it. A journal reused across two transactions would instead carry
    * the first transaction's transient writes into the second -- readable by
    * `TLOAD` -- and would report the first transaction's creations as the
    * second's, which is a destruction the second transaction may not perform.
    * Both are a state root apart from every other client. The clears cost
    * nothing and close that without the caller having to know it is owed.
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
    transient.clear()
    createdHere.clear()

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
      private[evm] val brought: Set[Address],
      private[evm] val transient: Map[(Address, Word), Word]
  )
