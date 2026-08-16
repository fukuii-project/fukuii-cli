package org.fukuii.types

import org.fukuii.bytes.{UInt256, UInt64}

/** Which of the two legacy signing schemes a transaction's `v` names.
  *
  * ==This is read out of the signature, never chosen==
  *
  * EIP-155 added replay protection by folding the chain identifier into `v`
  * rather than by adding a field, so a legacy transaction does not say which
  * scheme it used — the value of `v` is the only evidence. The specification
  * states the rule in the recovery direction in as many words: a `v` of
  * `CHAIN_ID * 2 + 35` or `+ 36` means nine elements were hashed, while 27 and
  * 28 mean six were and "remain valid and continue to operate under the same
  * rules as previously".
  *
  * That is why the recovery preimage cannot be selected at compile time, and
  * so why the signing projection is a function rather than a codec instance.
  */
enum SignatureScheme:

  /** `v` is 27 or 28: six elements were signed and no chain identifier is
    * recoverable, so the signature is valid on every network.
    */
  case Unprotected

  /** `v` is `chainId * 2 + 35` or `+ 36`: nine elements were signed, and the
    * chain identifier is recovered from `v` itself.
    */
  case Protected(chainId: UInt64)

object SignatureScheme:

  /** A `v` that names neither scheme. */
  enum Error:
    case NotASignatureV(v: BigInt)
    case ChainIdTooLarge(v: BigInt)

  private val UnprotectedLow: BigInt = 27
  private val UnprotectedHigh: BigInt = 28
  private val ProtectedBase: BigInt = 35

  /** Classifies a legacy `v`.
    *
    * Returns the reason rather than defaulting when `v` names neither scheme.
    * Falling back to the unprotected reading would recover a well-formed but
    * WRONG sender address, which is worse than failing: a wrong sender is a
    * valid-looking account that never authorized anything.
    */
  def of(v: UInt256): Either[Error, SignatureScheme] =
    val raw = v.toBigInt
    if raw == UnprotectedLow || raw == UnprotectedHigh then Right(Unprotected)
    else if raw < ProtectedBase then Left(Error.NotASignatureV(raw))
    else
      // v = chainId * 2 + 35 + parity, so the parity is the low bit of v - 35.
      val chainId = (raw - ProtectedBase) / 2
      UInt64.fromBigInt(chainId) match
        case Right(id) => Right(Protected(id))
        case Left(_)   => Left(Error.ChainIdTooLarge(raw))
