package org.fukuii.types

import org.fukuii.bytes.{Address, UInt256}
import org.fukuii.crypto.{Keccak256, Secp256k1, Signature}

/** Recovering the account that signed a transaction.
  *
  * ==The sender is not carried; it is derived==
  *
  * No transaction has a sender field. The account is whichever one produced
  * the signature over the preimage, so recovering it means reconstructing that
  * preimage exactly — and for a legacy transaction which preimage was used is
  * decided by the `v` on the transaction itself. Getting that wrong does not
  * fail: it recovers a different, well-formed address that never authorized
  * anything.
  *
  * ==An address is the tail of the key's digest==
  *
  * `keccak` of the public key with its encoding prefix removed, keeping the
  * low twenty bytes. The prefix is dropped because the digest is taken over
  * the coordinate pair alone.
  */
object Sender:

  /** The number of leading bytes of an uncompressed point that are not
    * coordinate data.
    */
  private val PointPrefixWidth: Int = 1

  enum Error:
    /** The `v` names no signing scheme, so no preimage can be built. */
    case UnreadableScheme(cause: SignatureScheme.Error)

    /** The curve rejected the signature, or it recovers no point. */
    case Unrecoverable

  /** The account that signed this transaction.
    *
    * ==EIP-2's low-`s` bound is NOT applied here, and that is deliberate==
    *
    * EIP-2 declares a signature whose `s` exceeds half the curve order invalid,
    * and it takes effect at a stated fork rather than from genesis — so it is a
    * fork rule, and this layer holds none. The curve check below bounds `r` and
    * `s` to the field only.
    *
    * **The consequence a caller has to know:** a signature and its malleated
    * twin — `(r, n - s)` with the parity flipped — recover the SAME address
    * over the same preimage, while the transaction's own hash differs, because
    * `s` sits inside the bytes that are hashed. So one authorization can present
    * under two identifiers. That is not a wrong sender, and it is a duplicate
    * this layer cannot suppress: anything keyed on the hash — a pool, nonce
    * accounting, a receipt lookup — must apply the bound itself at the height
    * the network activates it.
    *
    * Stated here because the signature carries no fork context to remember it
    * by. A reference client's equivalent takes the activation flag as a
    * parameter and so cannot be called without answering the question.
    */
  def recover(transaction: Transaction): Either[Error, Address] =
    for
      preimage  <- SigningPreimage.hashForRecovery(transaction).left.map(Error.UnreadableScheme.apply)
      signature <- signatureOf(transaction).toRight(Error.Unrecoverable)
      publicKey <- Secp256k1.recoverPublicKey(preimage, signature).toRight(Error.Unrecoverable)
    yield addressOf(publicKey)

  /** The curve signature a transaction carries.
    *
    * The recovery identifier is the parity alone. A typed transaction states
    * it directly; a legacy one has it folded into `v` together with the chain
    * identifier, and the two cases recover it differently — which is why this
    * is not simply a field read.
    */
  def signatureOf(transaction: Transaction): Option[Signature] =
    transaction match
      case t: Transaction.Legacy =>
        SignatureScheme.of(t.v).toOption.flatMap: scheme =>
          val v = t.v.toBigInt
          val parity = scheme match
            case SignatureScheme.Unprotected   => v - 27
            case SignatureScheme.Protected(id) => v - 35 - (id.toBigInt * 2)
          if parity == 0 || parity == 1 then
            Some(Signature(t.r.toBigInt, t.s.toBigInt, parity.toInt))
          else None

      case other =>
        val parity = yParityOf(other).toBigInt
        if parity == 0 || parity == 1 then
          Some(Signature(rOf(other).toBigInt, sOf(other).toBigInt, parity.toInt))
        else None

  /** The low twenty bytes of the digest of the key's coordinate pair. */
  def addressOf(publicKey: IArray[Byte]): Address =
    val digest = Keccak256.hash(publicKey.drop(PointPrefixWidth)).toBytes
    Address
      .fromBytes(digest.drop(digest.length - Address.Width))
      .getOrElse(throw new IllegalStateException("a digest is wider than an address"))

  private def yParityOf(transaction: Transaction): UInt256 = transaction match
    case t: Transaction.AccessList => t.yParity
    case t: Transaction.DynamicFee => t.yParity
    case t: Transaction.Blob       => t.yParity
    case t: Transaction.SetCode    => t.yParity
    case t: Transaction.Legacy     => t.v

  private def rOf(transaction: Transaction): UInt256 = transaction match
    case t: Transaction.AccessList => t.r
    case t: Transaction.DynamicFee => t.r
    case t: Transaction.Blob       => t.r
    case t: Transaction.SetCode    => t.r
    case t: Transaction.Legacy     => t.r

  private def sOf(transaction: Transaction): UInt256 = transaction match
    case t: Transaction.AccessList => t.s
    case t: Transaction.DynamicFee => t.s
    case t: Transaction.Blob       => t.s
    case t: Transaction.SetCode    => t.s
    case t: Transaction.Legacy     => t.s
