package org.fukuii.types

import org.fukuii.bytes.{Hash, UInt256, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** The state of a single account: what the state trie stores at an address.
  *
  * ==Four fields here, three in the executable specification==
  *
  * That specification's own account dataclass carries `nonce`, `balance` and
  * `code_hash`, and takes the storage root as a separate argument at encoding
  * time — because its state object holds storage elsewhere, so an account
  * cannot name its own root.
  *
  * This carries the root as a field instead, and the reason is the codec
  * contract rather than a preference: [[org.fukuii.rlp.RlpCodec]] exists only
  * for whole-value encodings that round-trip, and an encoder needing an
  * argument the value does not hold cannot be one. Four fields is also what the
  * encoding has always been and what both reference clients model.
  *
  * ==The nonce is 64-bit by specification, not by convention==
  *
  * EIP-2681 limits an account nonce to between 0 and 2^64-1, so the machine
  * word is the exact width rather than a pragmatic choice — unlike the header's
  * quantities, where this project narrows what the specification leaves
  * unbounded. A nonce that did not fit was already invalid before it reached
  * here.
  *
  * @param storageRoot
  *   the root of this account's own storage trie. An account with no storage
  *   carries the empty-trie root, which is a real 32-byte value and not a
  *   zero — computing it needs the trie, so callers take it from there.
  * @param codeHash
  *   keccak of the account's code, and of the empty byte string for an account
  *   with none. Also a real hash rather than a zero.
  */
final case class Account(
    nonce: UInt64,
    balance: UInt256,
    storageRoot: Hash,
    codeHash: Hash
)

object Account:

  /** The number of fields, fixed. The account encoding has not changed across
    * any fork, so this is a constant rather than something a decoder reads.
    */
  val FieldCount: Int = 4

  /** `[nonce, balance, storageRoot, codeHash]`, in that order.
    *
    * The two roots are adjacent and the same type, so transposing them yields a
    * well-formed list that decodes into a different account — an account whose
    * code and storage have swapped identities. Only a vector catches that,
    * which is why the table certifying this carries rows where the two differ.
    */
  given accountCodec: RlpCodec[Account] with

    def encode(value: Account): RlpItem =
      RlpItem.Sequence(
        Vector(
          RlpCodec[UInt64].encode(value.nonce),
          RlpCodec[UInt256].encode(value.balance),
          RlpCodec[Hash].encode(value.storageRoot),
          RlpCodec[Hash].encode(value.codeHash)
        )
      )

    def decode(item: RlpItem): Either[RlpError, Account] = item match
      case RlpItem.Sequence(items) =>
        if items.length != FieldCount then Left(RlpError.WrongArity(FieldCount, items.length))
        else
          for
            nonce       <- RlpCodec[UInt64].decode(items(0))
            balance     <- RlpCodec[UInt256].decode(items(1))
            storageRoot <- RlpCodec[Hash].decode(items(2))
            codeHash    <- RlpCodec[Hash].decode(items(3))
          yield Account(nonce, balance, storageRoot, codeHash)
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
