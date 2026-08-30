package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, UInt64}
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

  /** Where a contract created with a SALT will live.
    *
    * ==The point of the operation is that this does NOT depend on the count==
    *
    * [[of]] reads the creator's transaction count, so an address is consumed by
    * every creation whether or not it succeeds and a creator cannot return to
    * one it has passed. This reads the salt and the initialization code
    * instead, so the same creator can compute the address before creating
    * anything, and can re-create at the same address after a self-destruct.
    * That is the whole of what EIP-1014 buys, and it is why the count is absent
    * here rather than merely unused.
    *
    * ==The preimage is a fixed shape and the leading byte is part of it==
    *
    * `keccak256(0xff ++ creator ++ salt ++ keccak256(initCode))`, taking the
    * low twenty bytes -- `ethereum/execution-specs` @ `20f7f6271a`,
    * `forks/constantinople/utils/address.py:88-92`. The `0xff` prefix is what
    * keeps this preimage out of the space RLP can produce, so no creation of
    * one kind can be made to collide with the other; the salt is a full word
    * and is encoded as its thirty-two bytes, not as a minimal integer.
    *
    * **The initialization code is hashed, not included.** A caller passing the
    * code where the digest belongs would produce a plausible address that no
    * other client computes.
    */
  def create2(creator: Address, salt: Word, initCode: Bytes): Address =
    val preimage: IArray[Byte] =
      IArray[Byte](0xff.toByte) ++ creator.toBytes ++ salt.toBytes.toIArray ++
        Keccak256.hash(initCode.toIArray).toBytes
    Address.fromBytesTruncating(Keccak256.hash(preimage).toBytes)
