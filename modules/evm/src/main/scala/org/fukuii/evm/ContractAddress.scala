package org.fukuii.evm

import org.fukuii.bytes.{Address, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpCodec, RlpItem}

/** Where a contract a running account creates will live.
  *
  * The address is settled by the creator and the transaction count it holds at
  * the moment of creation, so it is known before the creating code runs and
  * cannot be chosen. Both are encoded as a two-element list and hashed, and the
  * low twenty bytes of that digest are the address.
  *
  * The count is read before it is incremented, which is what makes two
  * creations by one account land at different addresses.
  */
object ContractAddress:

  def of(creator: Address, nonce: UInt64): Address =
    val encoded = Rlp.encode(
      RlpItem.Sequence(
        Vector(RlpCodec[Address].encode(creator), RlpCodec[UInt64].encode(nonce))
      )
    )
    Address.fromBytesTruncating(Keccak256.hash(encoded).toBytes)
