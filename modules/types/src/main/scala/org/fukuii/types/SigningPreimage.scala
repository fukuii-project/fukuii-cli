package org.fukuii.types

import org.fukuii.bytes.{Address, Hash, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpCodec, RlpItem}

/** The bytes a signature is taken over, for each transaction shape.
  *
  * ==Why this is a function and not a codec instance==
  *
  * Three properties disqualify it, and any one of them would be enough:
  *
  *   1. It is encode-only. Nothing reads a transaction back out of its signing
  *      hash, so an [[org.fukuii.rlp.RlpCodec]] instance would carry a
  *      `decode` member nothing could honestly implement.
  *   2. It is not a whole-value encoding but a projection with the signature
  *      omitted, so it does not identify the transaction it came from.
  *   3. Which projection applies is chosen at run time. Since EIP-155 the
  *      preimage used for RECOVERY depends on the `v` actually observed —
  *      not on the transaction's type, and not on anything a compile-time
  *      instance could dispatch on.
  *
  * Both reference implementations agree: neither routes a signing preimage
  * through the machinery that encodes whole transactions.
  *
  * ==The projection is one rule, not five==
  *
  * Every payload ends with exactly its three signature elements, so dropping
  * them yields the signed field list in all five cases. Each resulting length
  * was checked against its own proposal rather than trusted: 9 to 6 for the
  * legacy shape, 11 to 8, 12 to 9, 14 to 11, and 13 to 10.
  *
  * ==The type byte is part of the signed data==
  *
  * EIP-2718 requires it, so a signature over one type cannot be replayed as a
  * signature over another. Each typed proposal states its own instance of that
  * rule and they agree.
  */
object SigningPreimage:

  /** EIP-7702's domain separator for an authorization tuple. */
  val AuthorizationMagic: Byte = 0x05

  /** The bytes signed by whoever sent this transaction.
    *
    * @param chainId
    *   used only by the legacy shape, and only to ask for the
    *   replay-protected form — `None` requests the pre-EIP-155 one. Every
    *   typed payload carries its own chain identifier inside the signed
    *   fields, so this argument does not reach them.
    */
  def forSigning(transaction: Transaction, chainId: Option[UInt64]): IArray[Byte] =
    transaction match
      case t: Transaction.Legacy =>
        chainId match
          case None     => Rlp.encode(RlpItem.Sequence(signedFields(t)))
          case Some(id) => Rlp.encode(RlpItem.Sequence(protectedFields(t, id)))
      case other => typedPreimage(other)

  /** The bytes whose signature must be recovered to learn the sender — which
    * for a legacy transaction is decided by the `v` it carries, not by the
    * caller.
    *
    * The chain identifier is read back OUT of the signature here rather than
    * supplied, which is [[SignatureScheme]]'s whole job.
    */
  def forRecovery(transaction: Transaction): Either[SignatureScheme.Error, IArray[Byte]] =
    transaction match
      case t: Transaction.Legacy =>
        SignatureScheme.of(t.v).map:
          case SignatureScheme.Unprotected      => Rlp.encode(RlpItem.Sequence(signedFields(t)))
          case SignatureScheme.Protected(id)    => Rlp.encode(RlpItem.Sequence(protectedFields(t, id)))
      case other => Right(typedPreimage(other))

  /** The bytes an authorization tuple's own signer signed:
    * `MAGIC || rlp([chainId, address, nonce])`.
    *
    * A separate signature from the transaction's, over a separate preimage, by
    * a separate account — which is the point of the tuple.
    */
  def forAuthorization(authorization: Authorization): IArray[Byte] =
    val body = Rlp.encode(
      RlpItem.Sequence(
        Vector(
          RlpCodec[UInt256].encode(authorization.chainId),
          RlpCodec[Address].encode(authorization.address),
          RlpCodec[UInt64].encode(authorization.nonce)
        )
      )
    )
    IArray(AuthorizationMagic) ++ body

  def hashForSigning(transaction: Transaction, chainId: Option[UInt64]): Hash =
    Keccak256.hash(forSigning(transaction, chainId))

  def hashForRecovery(transaction: Transaction): Either[SignatureScheme.Error, Hash] =
    forRecovery(transaction).map(Keccak256.hash)

  def hashForAuthorization(authorization: Authorization): Hash =
    Keccak256.hash(forAuthorization(authorization))

  /** The payload's elements with its trailing signature dropped. */
  private def signedFields(transaction: Transaction): Vector[RlpItem] =
    Transaction.elementsOf(transaction).dropRight(Transaction.SignatureFieldCount)

  /** EIP-155's nine: the signed six, then the chain identifier and two zeros.
    *
    * The two zeros are ENCODED SCALARS, so each is the empty byte string and
    * not a zero byte — writing `0x00` there would be a different preimage and
    * so a different sender on every replay-protected transaction.
    */
  private def protectedFields(t: Transaction.Legacy, chainId: UInt64): Vector[RlpItem] =
    signedFields(t) ++ Vector(
      RlpCodec[UInt64].encode(chainId),
      RlpCodec[UInt256].encode(UInt256.Zero),
      RlpCodec[UInt256].encode(UInt256.Zero)
    )

  private def typedPreimage(transaction: Transaction): IArray[Byte] =
    IArray(transaction.typeNumber.toByte) ++ Rlp.encode(RlpItem.Sequence(signedFields(transaction)))
