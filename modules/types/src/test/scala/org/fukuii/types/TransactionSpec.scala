package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hex, UInt256, UInt64}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** The boundaries the shipped corpus cannot reach.
  *
  * Every transaction in a block is by construction a type some proposal
  * defines, carrying a signature some account produced — so the corpus says
  * nothing about the edges of the envelope itself. Each case here was added
  * because a mutation survived the corpus rows alone.
  */
class TransactionSpec extends AnyFlatSpec:

  private val anAddress = Address.fromHex("c0f6dc9e5836f54caadbf59cc69346c508e1992b").toOption.get

  private def legacy(v: BigInt): Transaction.Legacy =
    Transaction.Legacy(
      nonce = UInt64.Zero,
      gasPrice = UInt256.Zero,
      gasLimit = UInt64.Zero,
      to = Some(anAddress),
      value = UInt256.Zero,
      data = Bytes.Empty,
      v = UInt256.fromBigInt(v).toOption.get,
      r = UInt256.fromBigInt(BigInt(1)).toOption.get,
      s = UInt256.fromBigInt(BigInt(2)).toOption.get
    )

  /** An envelope carrying a well-formed payload under a type number no
    * proposal defines.
    */
  private def envelopeOfType(typeByte: Int): IArray[Byte] =
    IArray(typeByte.toByte) ++ Rlp.encode(RlpItem.Sequence(Vector(RlpItem.Bytes(IArray.empty[Byte]))))

  "fromCanonicalBytes" should "refuse the largest type number EIP-2718 admits, as an unknown type" in {
    assert(
      Transaction.fromCanonicalBytes(envelopeOfType(Transaction.MaxTypeNumber)) ==
        Left(RlpError.UnknownDiscriminant(Transaction.MaxTypeNumber)),
      "0x7f is a legal type number with no payload defined, so it is unknown rather than legacy"
    )
  }

  it should "refuse a type number between the modeled set and the envelope's ceiling" in {
    assert(
      Transaction.fromCanonicalBytes(envelopeOfType(0x05)) == Left(RlpError.UnknownDiscriminant(0x05)),
      "an undefined type is refused by number, not by payload shape"
    )
  }

  it should "read a leading byte above the ceiling as a legacy list rather than a type" in {
    val encoded = Transaction.canonicalBytes(legacy(27))
    assert(
      Transaction.fromCanonicalBytes(encoded).map(_.typeNumber) == Right(0),
      "an RLP list prefix is at least 0xc0, which no type number can collide with"
    )
  }

  it should "reject empty input" in {
    assert(Transaction.fromCanonicalBytes(IArray.empty[Byte]) == Left(RlpError.EmptyInput))
  }

  /** The mutation that flipped EIP-155's base survived the corpus rows,
    * because integer division hides the error at even parity. Both parities
    * are pinned here so the arithmetic is exercised on the side that shows it.
    */
  "SignatureScheme" should "read the unprotected scheme from a v of 27" in {
    assert(SignatureScheme.of(legacy(27).v) == Right(SignatureScheme.Unprotected))
  }

  it should "read the unprotected scheme from a v of 28" in {
    assert(SignatureScheme.of(legacy(28).v) == Right(SignatureScheme.Unprotected))
  }

  it should "recover chain identifier 1 from the even-parity v of 37" in {
    assert(
      SignatureScheme.of(legacy(37).v) == Right(SignatureScheme.Protected(UInt64.fromLong(1).toOption.get))
    )
  }

  it should "recover chain identifier 1 from the odd-parity v of 38" in {
    assert(
      SignatureScheme.of(legacy(38).v) == Right(SignatureScheme.Protected(UInt64.fromLong(1).toOption.get))
    )
  }

  it should "recover a large chain identifier, where the doubling could overflow a smaller word" in {
    val id = BigInt(61)
    assert(
      SignatureScheme.of(legacy(id * 2 + 35).v) ==
        Right(SignatureScheme.Protected(UInt64.fromBigInt(id).toOption.get))
    )
  }

  it should "refuse a v that names neither scheme" in {
    assert(SignatureScheme.of(legacy(26).v).isLeft, "26 is below every scheme's range")
  }

  /** Contract creation is the empty byte string, and the zero address is a
    * real account twenty bytes wide. Collapsing them sends value to 0x0.
    */
  "the codec" should "encode an absent recipient as the empty string, not the zero address" in {
    val creation = legacy(27).copy(to = None)
    val encoded  = Hex.encode(Transaction.canonicalBytes(creation))
    assert(
      !encoded.contains("94" + "00" * 20),
      "an absent recipient must not encode as a twenty-byte address"
    )
  }

  it should "round-trip a contract creation" in {
    val creation = legacy(27).copy(to = None)
    assert(
      Transaction.fromCanonicalBytes(Transaction.canonicalBytes(creation)) == Right(creation)
    )
  }

  it should "distinguish a creation from a transfer to the zero address" in {
    val zero     = Address.fromBytes(IArray.fill(20)(0.toByte)).toOption.get
    val creation = Transaction.canonicalBytes(legacy(27).copy(to = None))
    val toZero   = Transaction.canonicalBytes(legacy(27).copy(to = Some(zero)))
    assert(Hex.encode(creation) != Hex.encode(toZero))
  }

  /** A legacy transaction nests into a block body as the list itself, so its
    * codec output must not acquire a string header.
    */
  "the block-body form" should "nest a legacy transaction as a sequence" in {
    assert(RlpCodec[Transaction].encode(legacy(27)).isInstanceOf[RlpItem.Sequence])
  }
