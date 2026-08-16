package org.fukuii.types

import org.fukuii.bytes.{Address, Hash}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** One entry of an access list: an account, and the storage slots of that
  * account a transaction declares it will touch.
  *
  * ==The storage keys are fixed-width, not scalars==
  *
  * EIP-2930 states the access list's shape as `[[{20 bytes}, [{32 bytes}...]]...]`
  * — a width, not a quantity. So a key of `0x00…01` encodes as thirty-two
  * bytes and not as one, and encoding it as a scalar would drop its leading
  * zeros and change the transaction's hash. Carrying the keys in a fixed-width
  * value type is what makes that mistake unavailable rather than merely
  * avoided.
  *
  * A key is a 32-byte word rather than a digest, so [[org.fukuii.bytes.Hash]]
  * is used here for its width and not for its provenance — the same deliberate
  * looseness [[Log]]'s topics carry, and for the same reason: the width is what
  * the encoding constrains, and a second value type over the same 32 bytes
  * would buy a distinction nothing at this layer reads.
  *
  * @param address
  *   the account whose slots are listed. Repeats are not rejected here —
  *   EIP-2930 permits them and charges for each, which is a gas rule and so
  *   belongs to the layer that owns gas.
  */
final case class AccessTuple(address: Address, storageKeys: Seq[Hash])

object AccessTuple:

  /** The number of RLP elements, fixed at two by EIP-2930's own shape. */
  val FieldCount: Int = 2

  /** `[address, [storageKey, ...]]`.
    *
    * The keys are a nested list rather than flattened siblings, so an entry
    * touching no slots carries an empty list and the arity stays two.
    */
  given accessTupleCodec: RlpCodec[AccessTuple] with

    def encode(value: AccessTuple): RlpItem =
      RlpItem.Sequence(
        Vector(
          RlpCodec[Address].encode(value.address),
          RlpCodec[Seq[Hash]].encode(value.storageKeys)
        )
      )

    def decode(item: RlpItem): Either[RlpError, AccessTuple] = item match
      case RlpItem.Sequence(items) =>
        if items.length != FieldCount then Left(RlpError.WrongArity(FieldCount, items.length))
        else
          for
            address <- RlpCodec[Address].decode(items(0))
            keys <- RlpCodec[Seq[Hash]].decode(items(1))
          yield AccessTuple(address, keys)
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
