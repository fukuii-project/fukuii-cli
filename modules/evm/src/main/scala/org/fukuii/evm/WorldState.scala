package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt64}

/** Everything the machine may ask of world state, and the whole of it.
  *
  * ==The machine declares this, rather than the state layer offering it==
  *
  * Both surveyed implementations put the interface on this side of the seam:
  * go-ethereum declares `StateDB` inside `core/vm` and lets `core/state`
  * satisfy it, and besu keeps `WorldUpdater` and `WorldView` inside its `evm`
  * module while the trie-backed implementation lives a layer out. The direction
  * matters because it is what decides who may change the surface: an interface
  * owned by the machine names what execution needs, so a storage layer cannot
  * widen it by accident, and a second implementation costs nothing.
  *
  * ==Every member here is one an operation of this fork reaches==
  *
  * The reads answer what `BALANCE`, `EXTCODESIZE`, `EXTCODECOPY`, `SLOAD`,
  * `CALL` and `CREATE` ask; the writes are what `SSTORE`, value transfer,
  * contract creation and `SELFDESTRUCT` perform. Nothing here exposes an
  * account record, a code hash or a storage root: an operation that asked for
  * one would be reaching past what it executes, and the two indirections a code
  * lookup needs -- address to code hash, code hash to bytes -- are the
  * implementation's business rather than the caller's.
  *
  * The set is go-ethereum's `StateDB` narrowed to this fork. Its access-list,
  * transient-storage and self-destruct members answer proposals that arrive
  * later, and none of them has an operation here that would call it.
  *
  * ==Nothing here says which state, or when==
  *
  * A view is supplied to the machine rather than found by it, so this admits a
  * view over any point in the chain's history as readily as over the head. That
  * is not a hypothetical convenience: a consensus mechanism that resolves its
  * validator set by executing a call has to name the block it reads at, and a
  * seam wired to a single mutable head would foreclose it. Keeping the choice
  * with the caller costs nothing here and is what leaves it open.
  *
  * ==Absence is answered as a value, never as a failure==
  *
  * An account that does not exist has a zero balance, a zero nonce and no code,
  * and a slot that was never written reads as zero. The specification says so
  * directly: its `get_account` returns `EMPTY_ACCOUNT` where no account is
  * found, and its `get_storage` returns zero for a key that was never set. So
  * none of these answers is optional, and an operation reading a fresh address
  * takes the same path as one reading a busy contract.
  *
  * [[accountExists]] is the one read that distinguishes the two, because one
  * price depends on it: a message call to an account this state has never held
  * costs more than one to an account it has.
  *
  * ==A write to an absent account creates it==
  *
  * Every writing member below is total in the same way the reads are: it
  * applies to an account that does not exist by bringing one into being with
  * the empty account's other fields. That is the specification's `modify_state`,
  * which reads through `get_account` -- so it sees the empty account -- and
  * writes the result back unconditionally.
  */
trait WorldState:

  /** The balance at `address`, and zero where no account exists. */
  def balanceOf(address: Address): Word

  /** The transaction count at `address`, and zero where no account exists. */
  def nonceOf(address: Address): UInt64

  /** The code at `address`: empty where the account has none, and empty where
    * no account exists at all.
    */
  def codeOf(address: Address): Bytes

  /** Whether this state holds an account at `address` at all.
    *
    * Distinct from every other read, which answer for an absent account with
    * the empty account's value. `CALL` prices its destination by this, and a
    * creation refuses an address this answers for.
    */
  def accountExists(address: Address): Boolean

  /** Whether `address` has anything in its storage.
    *
    * Asked only by contract creation, which will not deploy over an account
    * that has some -- see [[org.fukuii.evm.Interpreter]], where that rule is
    * applied and where the two authorities behind it are recorded as
    * disagreeing.
    */
  def hasStorage(address: Address): Boolean

  /** The value at `slot` of `address`'s storage, and zero where none was
    * written.
    */
  def storageAt(address: Address, slot: Word): Word

  /** Writes `value` to `slot` of `address`'s storage.
    *
    * A zero `value` is passed through as a zero rather than being turned into a
    * removal here. Where that becomes a removal is an implementation's
    * decision, because it is only at the point bytes reach a trie that the
    * distinction between "holds zero" and "holds nothing" stops being a
    * representation detail -- see [[StateTrieWorldState]], which is where this
    * project applies it, and which cites the two sources that place it there.
    */
  def setStorage(address: Address, slot: Word, value: Word): Unit

  def setBalance(address: Address, value: Word): Unit

  /** Writes `value` as the transaction count at `address`.
    *
    * An absolute write rather than an increment, which is go-ethereum's
    * `SetNonce` and is what lets a journal hold the pending value rather than a
    * count of increments to replay. The specification's `increment_nonce` is
    * then a read and a write at the one caller that needs it.
    */
  def setNonce(address: Address, value: UInt64): Unit

  /** Makes `code` the code at `address`. */
  def setCode(address: Address, code: Bytes): Unit

  /** Brings an account at `address` into being if this state holds none, and
    * changes nothing if it does.
    *
    * Every invocation does this to the account it runs as, before any of its
    * code runs, which is why an ordinary transfer to an address nothing has
    * used leaves an account behind at this fork.
    */
  def touch(address: Address): Unit
