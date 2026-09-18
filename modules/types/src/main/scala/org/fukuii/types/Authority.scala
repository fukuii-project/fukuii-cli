package org.fukuii.types

import org.fukuii.bytes.Address
import org.fukuii.crypto.{Secp256k1, Signature}

/** Whose account an authorization names.
  *
  * ==Separate from [[Sender]] because the bounds differ, not because the curve
  * does==
  *
  * Both recover an address from a signature over a digest, and both end at
  * [[Sender.addressOf]]. What differs is what each accepts before recovering: a
  * transaction's signature is bounded by the rules its fork resolves, some of
  * which admit a high `s` and some of which do not, while an authorization's
  * bounds are the document's own and do not vary by fork.
  *
  * ==The bounds are STATED here rather than left to the curve==
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/vm/eoa_delegation.py:99-104` refuses a parity
  * outside `{0, 1}`, an `r` that is zero or at or above the group order, and an
  * `s` that is zero or above half the order. **The half-order bound is not a
  * malleability preference here, it is the document's rule** -- every
  * authorization is bounded that way at every fork that carries the document,
  * so no rule set resolves it.
  *
  * ==A refusal is not a chain result==
  *
  * An authorization failing any of these is SKIPPED, not a reason to refuse the
  * transaction carrying it. The transaction still runs and still pays for the
  * authorization slot; the authority simply is not set. That is the document's
  * own behavior and the reason this returns an option rather than an either --
  * there is no reason to report, because no caller may act on one.
  */
object Authority:

  /** The order of the curve's subgroup, which bounds `r` and, halved, `s`. */
  val GroupOrder: BigInt =
    BigInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

  /** The account that signed `authorization`, or nothing where the signature is
    * outside the document's bounds or recovers to no key.
    */
  def of(authorization: Authorization): Option[Address] =
    val parity = authorization.yParity.toBigInt
    val r = authorization.r.toBigInt
    val s = authorization.s.toBigInt
    if parity != 0 && parity != 1 then None
    else if r <= 0 || r >= GroupOrder then None
    else if s <= 0 || s > GroupOrder / 2 then None
    else
      Secp256k1
        .recoverPublicKey(SigningPreimage.hashForAuthorization(authorization), Signature(r, s, parity.toInt))
        .map(Sender.addressOf)
