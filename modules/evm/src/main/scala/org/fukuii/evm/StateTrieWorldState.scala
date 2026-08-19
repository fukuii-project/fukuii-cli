package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.{StateTrie, Trie, TrieError}
import org.fukuii.types.Account

/** [[WorldState]] over the trie a state root is computed from.
  *
  * ==Where the zero rule is applied, and why it is here rather than earlier==
  *
  * A storage trie holds no key for a slot whose value is zero, so writing a
  * zero has to become a removal somewhere. Both sources place that at the point
  * a value reaches the trie rather than at the point an operation produces it.
  * The executable specification's `trie_set` deletes the key when the value
  * equals the trie's default, which for a storage trie is zero, and the value
  * that reached it was recorded as an ordinary zero on the way. go-ethereum
  * does the same one layer out: `SetState` stores the zero, and `updateTrie`
  * turns it into `DeleteStorage` when it flushes.
  *
  * Following them keeps a read after a zero write answering zero without a
  * special case anywhere above, because the slot genuinely holds zero until it
  * reaches this point and holds nothing afterwards -- and those are the same
  * answer.
  *
  * ==An account leaf carries its own storage root, so a storage write rewrites
  * it==
  *
  * The trie below derives an account's storage root from that account's own
  * storage trie at the moment the account is written, which means a slot
  * written and no account written after it leaves the leaf committing to the
  * root the storage had beforehand. This seam has no way to hand that
  * obligation to its caller -- nothing in [[WorldState]] writes an account --
  * so [[setStorage]] discharges it here, and the cost is one account encoding
  * per slot written.
  *
  * ==A value is stored as the RLP of its minimal form==
  *
  * Not as a fixed-width word. `RlpCodec` encodes a 256-bit quantity by the
  * scalar rule, which is what the specification's own `rlp.encode` of a storage
  * value produces and what go-ethereum builds by trimming leading zeroes before
  * encoding. A fixed-width value would encode to different bytes and commit to
  * a different storage root.
  *
  * ==Corruption is raised, not answered==
  *
  * An account or a storage value the trie holds and cannot decode, or code an
  * account names and the store does not have, is a broken node rather than a
  * state of the chain. Each is raised, which is what the trie below already
  * does when it meets an undecodable node, rather than being folded into the
  * zero that a genuinely absent value answers with. Folding them together would
  * make a damaged database read as an empty account.
  */
final class StateTrieWorldState(state: StateTrie) extends WorldState:

  def balanceOf(address: Address): Word =
    accountAt(address).fold(Word.Zero)(account => Word(account.balance.toBigInt))

  def codeOf(address: Address): Bytes =
    accountAt(address).fold(Bytes.Empty) { account =>
      state
        .getCode(account.codeHash)
        .getOrElse(
          throw new IllegalStateException(
            "an account names code the store does not hold, under the digest " + account.codeHash.toHex
          )
        )
    }

  def storageAt(address: Address, slot: Word): Word =
    state.getStorage(address, quantity(slot)) match
      case None          => Word.Zero
      case Some(encoded) =>
        RlpCodec
          .decodeFrom[UInt256](encoded.toIArray)
          .fold(
            error =>
              throw new IllegalStateException(
                "storage holds an undecodable value at slot " + encoded.toHex + ": " + error
              ),
            value => Word(value.toBigInt)
          )

  def setStorage(address: Address, slot: Word, value: Word): Unit =
    if value.isZero then state.deleteStorage(address, quantity(slot))
    else state.putStorage(address, quantity(slot), Bytes.fromIArray(RlpCodec.encodeTo(quantity(value))))
    val account = accountAt(address).getOrElse(EmptyAccount)
    state.putAccount(address, account.nonce, account.balance, account.codeHash)

  private val EmptyAccount: Account =
    Account(UInt64.Zero, UInt256.Zero, Trie.EmptyRoot, StateTrie.EmptyCodeHash)

  private def accountAt(address: Address): Option[Account] =
    state
      .getAccount(address)
      .fold(
        (error: TrieError) => throw new IllegalStateException("the account trie holds a malformed account: " + error),
        found => found
      )

  /** The machine's word as the quantity the trie is keyed and valued by.
    *
    * A word is already inside `[0, 2^256)` because every operation that
    * produces one reduces modulo that, so the refusal this cannot provoke is
    * the one the quantity's constructor exists for.
    */
  private def quantity(value: Word): UInt256 =
    UInt256
      .fromBigInt(value.toBigInt)
      .getOrElse(throw new IllegalStateException("a machine word does not fit a 256-bit quantity"))
