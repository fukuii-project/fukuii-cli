package org.fukuii.types

import org.fukuii.bytes.{Address, UInt256, UInt64, UInt8}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** One tuple of a set-code transaction's authorization list: a signed
  * statement by an account that it wishes some address's code to execute in
  * its own context.
  *
  * ==Every width here is a bound the specification states, not a choice==
  *
  * EIP-7702 lists them as assertions — `chain_id < 2**256`, `len(address) ==
  * 20`, `nonce < 2**64`, `y_parity < 2**8`, `r < 2**256`, `s < 2**256` — and
  * the executable specification types the tuple to match. So each field's type
  * here is that bound made unrepresentable rather than checked, and the
  * conformance corpus certifies the rejecting direction: its published
  * invalid set carries a 33-byte chain id, a 19- and a 21-byte address, and a
  * `y_parity` of 256, every one of them expected to fail when the bytes are
  * read.
  *
  * ==Two fields are narrower than the same-named fields of the transaction==
  *
  * The enclosing transaction's `chainId` is the machine word and its
  * `yParity` is a 256-bit quantity; here they are 256-bit and 8-bit
  * respectively. That asymmetry is not a slip in either place — both
  * specifications state it, and the executable specification's two dataclasses
  * carry the two different widths.
  *
  * @param nonce
  *   the authorizing account's, not the transaction sender's.
  * @param yParity
  *   admits the full byte the specification bounds it to, rather than only 0
  *   and 1. A value of 2 through 255 encodes, decodes, and then fails to
  *   recover an authority — which is a signature question owned above this
  *   layer, so refusing it here would answer it in the wrong place.
  */
final case class Authorization(
    chainId: UInt256,
    address: Address,
    nonce: UInt64,
    yParity: UInt8,
    r: UInt256,
    s: UInt256
)

object Authorization:

  /** The tuple's arity, fixed at six by EIP-7702's own shape.
    *
    * Enforced exactly: the corpus publishes five- and seven-element tuples in
    * its rejection set, so both directions are certified rather than assumed.
    */
  val FieldCount: Int = 6

  /** `[chainId, address, nonce, yParity, r, s]`. */
  given authorizationCodec: RlpCodec[Authorization] with

    def encode(value: Authorization): RlpItem =
      RlpItem.Sequence(
        Vector(
          RlpCodec[UInt256].encode(value.chainId),
          RlpCodec[Address].encode(value.address),
          RlpCodec[UInt64].encode(value.nonce),
          RlpCodec[UInt8].encode(value.yParity),
          RlpCodec[UInt256].encode(value.r),
          RlpCodec[UInt256].encode(value.s)
        )
      )

    def decode(item: RlpItem): Either[RlpError, Authorization] = item match
      case RlpItem.Sequence(items) =>
        if items.length != FieldCount then Left(RlpError.WrongWidth(FieldCount, items.length))
        else
          for
            chainId <- RlpCodec[UInt256].decode(items(0))
            address <- RlpCodec[Address].decode(items(1))
            nonce   <- RlpCodec[UInt64].decode(items(2))
            parity  <- RlpCodec[UInt8].decode(items(3))
            r       <- RlpCodec[UInt256].decode(items(4))
            s       <- RlpCodec[UInt256].decode(items(5))
          yield Authorization(chainId, address, nonce, parity, r, s)
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
