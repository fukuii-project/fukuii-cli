package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.trie.{StateTrie, Trie}
import org.scalatest.flatspec.AnyFlatSpec

/** The seam over the trie a state root is computed from.
  *
  * The zero rule is the reason this spec exists rather than a map double: the
  * expected values are `ethereum/execution-specs` at `ccaaaba58` --
  * `merkle_patricia_trie.trie_set`, which deletes a key whose value equals the
  * trie's default, and `state_tracker.get_storage`, which answers zero for a
  * key that was never set. A slot written to zero must leave the storage trie
  * holding no key at all, and only a real trie can show that.
  */
class StateTrieWorldStateSpec extends AnyFlatSpec:

  private val owner: Address = EvmFixtures.address(0x22)
  private val stranger: Address = EvmFixtures.address(0x44)

  private def slot(number: Long): UInt256 = UInt256.fromLong(number).toOption.get

  private def wei(amount: Long): UInt256 = UInt256.fromLong(amount).toOption.get

  private def account(state: StateTrie, address: Address, balance: Long, code: Bytes): Unit =
    state.putAccount(address, UInt64.Zero, wei(balance), state.putCode(code))

  private def over(state: StateTrie): WorldState = new StateTrieWorldState(state)

  "a balance" should "be read from the account trie" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 1234, Bytes.Empty)
    assert(over(state).balanceOf(owner) == EvmFixtures.word(1234), "the balance the account leaf carries")
  }

  it should "be zero where no account exists" in
    assert(
      over(EvmFixtures.stateTrie()).balanceOf(stranger) == Word.Zero,
      "an address with no account answers as the empty account, which the specification gives a zero balance"
    )

  "code" should "be read through the account's code hash" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, EvmFixtures.bytesOf("6001600155"))
    assert(over(state).codeOf(owner) == EvmFixtures.bytesOf("6001600155"), "the bytes the account's digest names")
  }

  it should "be empty for an account carrying none" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 7, Bytes.Empty)
    assert(over(state).codeOf(owner) == Bytes.Empty, "the empty code is answered rather than reported missing")
  }

  it should "be empty where no account exists" in
    assert(
      over(EvmFixtures.stateTrie()).codeOf(stranger) == Bytes.Empty,
      "no account and no code answer alike, because neither is a fault"
    )

  "a storage read" should "be zero for a slot never written" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    assert(over(state).storageAt(owner, EvmFixtures.word(1)) == Word.Zero, "an absent slot reads as zero")
  }

  it should "answer with what was written" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    assert(over(state).storageAt(owner, EvmFixtures.word(1)) == EvmFixtures.word(42), "the value round-trips")
  }

  "a storage write" should "store the RLP of the value's minimal form" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(0x0100))
    assert(
      state.getStorage(owner, slot(1)).contains(EvmFixtures.bytesOf("820100")),
      "a fixed-width value would encode to different bytes and commit to a different storage root"
    )
  }

  "a storage write of zero" should "leave the trie holding no key at all" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), Word.Zero)
    assert(
      state.getStorage(owner, slot(1)).isEmpty,
      "storing RLP of zero would put a leaf where the storage trie must hold none"
    )
  }

  it should "remove a slot that held a value" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    over(state).setStorage(owner, EvmFixtures.word(1), Word.Zero)
    assert(state.getStorage(owner, slot(1)).isEmpty, "clearing a slot removes its key rather than rewriting it")
  }

  it should "return the storage root to the empty one" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    over(state).setStorage(owner, EvmFixtures.word(1), Word.Zero)
    assert(
      state.storageRoot(owner) == Trie.EmptyRoot,
      "an account whose every slot was cleared commits to the empty trie, which is what a client agrees with"
    )
  }

  it should "read back as zero" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    over(state).setStorage(owner, EvmFixtures.word(1), Word.Zero)
    assert(
      over(state).storageAt(owner, EvmFixtures.word(1)) == Word.Zero,
      "holding zero and holding nothing are the same answer to a reader, which is what makes the removal safe"
    )
  }

  "one account's storage" should "be kept apart from another's" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    account(state, stranger, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    assert(
      over(state).storageAt(stranger, EvmFixtures.word(1)) == Word.Zero,
      "a slot number is not a global key, so the same number under two accounts is two slots"
    )
  }

  "an account leaf" should "commit to the storage root a write has just produced" in {
    val state = EvmFixtures.stateTrie()
    account(state, owner, 0, Bytes.Empty)
    over(state).setStorage(owner, EvmFixtures.word(1), EvmFixtures.word(42))
    assert(
      state.getAccount(owner).toOption.flatten.map(_.storageRoot).contains(state.storageRoot(owner)),
      "a leaf naming the root its storage had beforehand commits the account to storage it no longer has"
    )
  }
