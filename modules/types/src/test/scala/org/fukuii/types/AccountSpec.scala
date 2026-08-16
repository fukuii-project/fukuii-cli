package org.fukuii.types

import org.fukuii.bytes.{Hash, UInt256, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What an account encoding must refuse.
  *
  * [[AccountPropSpec]] pins what a valid account encodes to. This pins the
  * boundary, and the arity direction matters more than it looks: unlike a block
  * header, whose tail grows by proposal and which must therefore tolerate
  * elements it does not model, **an account has had exactly four fields at
  * every fork.** So a longer list is malformed here where it would be
  * forward-compatible there, and a decoder that read the first four and ignored
  * the rest would accept a shape no specification defines.
  *
  * That direction had no test until a mutation survived: relaxing the arity
  * check from `!=` to `<` passed the whole suite, because every vector is
  * exactly four elements and nothing ever offered five.
  */
class AccountSpec extends AnyFlatSpec:

  private def hash(b: Byte): Hash = Hash.fromBytesTruncating(IArray.fill(32)(b))

  private val account = Account(
    nonce = UInt64.fromBigInt(BigInt(7)).toOption.get,
    balance = UInt256.fromBigInt(BigInt(11)).toOption.get,
    storageRoot = hash(1),
    codeHash = hash(2)
  )

  private def itemsOf(item: RlpItem): Vector[RlpItem] = item match
    case RlpItem.Sequence(items) => items
    case _: RlpItem.Bytes        => Vector.empty

  private val encoded  = RlpCodec[Account].encode(account)
  private val tooLong  = RlpItem.Sequence(itemsOf(encoded) :+ RlpItem.Bytes(IArray.empty))
  private val tooShort = RlpItem.Sequence(itemsOf(encoded).dropRight(1))

  "an account of five elements" should "be refused rather than truncated to four" in {
    assert(
      RlpCodec[Account].decode(tooLong) == Left(RlpError.WrongArity(Account.FieldCount, 5)),
      "the account encoding has never grown, so a fifth element is malformed"
    )
  }

  "an account of three elements" should "be refused" in {
    assert(
      RlpCodec[Account].decode(tooShort) == Left(RlpError.WrongArity(Account.FieldCount, 3)),
      "four fields or none"
    )
  }

  "a byte string where an account is expected" should "be refused" in {
    assert(
      RlpCodec[Account].decode(RlpItem.Bytes(IArray.empty)) == Left(RlpError.ExpectedSequence),
      "an account is a list"
    )
  }
