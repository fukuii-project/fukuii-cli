package org.fukuii.types

import org.fukuii.bytes.{Address, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** A validator withdrawal, pushed from the consensus layer to the execution
  * layer as an operation rather than as a user transaction.
  *
  * @param index
  *   a global counter across the entire sequence of withdrawals, not a position
  *   within this block's list
  * @param validatorIndex
  *   the withdrawing validator, on the consensus layer
  * @param address
  *   the recipient of the withdrawn ether
  * @param amount
  *   in Gwei, not Wei
  */
final case class Withdrawal(index: UInt64, validatorIndex: UInt64, address: Address, amount: UInt64)

object Withdrawal:

  /** The number of fields, fixed. Unlike a block header's, a withdrawal's shape
    * has not changed since it was introduced, so this is a constant rather than
    * something a decoder reads from the input.
    */
  val FieldCount: Int = 4

  /** `[index, validator_index, address, amount]`, in that order.
    *
    * The order is the whole of the encoding, and the first two fields are the
    * same type — so transposing them yields a well-formed list that decodes
    * without complaint into a different withdrawal. Nothing but a published
    * vector catches that, which is why the table that certifies this carries the
    * corpus's two rows where the counters differ.
    */
  given withdrawalCodec: RlpCodec[Withdrawal] with

    def encode(value: Withdrawal): RlpItem =
      RlpItem.Sequence(
        Vector(
          RlpCodec[UInt64].encode(value.index),
          RlpCodec[UInt64].encode(value.validatorIndex),
          RlpCodec[Address].encode(value.address),
          RlpCodec[UInt64].encode(value.amount)
        )
      )

    def decode(item: RlpItem): Either[RlpError, Withdrawal] = item match
      case RlpItem.Sequence(items) =>
        if items.length != FieldCount then Left(RlpError.WrongArity(FieldCount, items.length))
        else
          for
            index          <- RlpCodec[UInt64].decode(items(0))
            validatorIndex <- RlpCodec[UInt64].decode(items(1))
            address        <- RlpCodec[Address].decode(items(2))
            amount         <- RlpCodec[UInt64].decode(items(3))
          yield Withdrawal(index, validatorIndex, address, amount)
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
