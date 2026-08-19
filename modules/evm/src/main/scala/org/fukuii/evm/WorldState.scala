package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}

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
  * ==Four operations, because four is what the operations need==
  *
  * A balance, an account's code, a storage slot, and a storage write. Nothing
  * here exposes an account record, a code hash, a nonce or a root: an operation
  * that asked for one would be reaching past what it executes, and the two
  * indirections a code lookup needs -- address to code hash, code hash to bytes
  * -- are the implementation's business rather than the caller's.
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
  * An account that does not exist has a zero balance and no code, and a slot
  * that was never written reads as zero. The specification says so directly:
  * its `get_account` returns `EMPTY_ACCOUNT` where no account is found, and its
  * `get_storage` returns zero for a key that was never set. So none of these
  * answers is optional, and an operation reading a fresh address takes the same
  * path as one reading a busy contract.
  */
trait WorldState:

  /** The balance at `address`, and zero where no account exists. */
  def balanceOf(address: Address): Word

  /** The code at `address`: empty where the account has none, and empty where
    * no account exists at all.
    */
  def codeOf(address: Address): Bytes

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
