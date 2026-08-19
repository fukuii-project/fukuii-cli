package org.fukuii.evm

import org.fukuii.bytes.UInt64
import org.scalatest.flatspec.AnyFlatSpec

/** Where a contract a running account creates will live.
  *
  * The formula is `ethereum/execution-specs` at `ccaaaba58`,
  * `frontier/utils/address.py` `compute_contract_address`, and
  * `ethereum/go-ethereum` at `6bb0588ad`, `crypto/crypto.go` `CreateAddress`:
  * the two-element list of creator and count, hashed, low twenty bytes. The
  * expected addresses below were produced outside this project by two
  * independent Keccak implementations agreeing on the same digest.
  */
class ContractAddressSpec extends AnyFlatSpec:

  private val creator = EvmFixtures.address(0x22)

  private def count(value: Long): UInt64 = UInt64.fromBits(value)

  "the first account a creator makes" should "be named by the creator and a count of zero" in
    assert(
      ContractAddress.of(creator, count(0L)).toHex == "659b375d76a8e9a2c68da8818022d6561aa60845",
      "the digest of the encoded pair, low twenty bytes"
    )

  "the next account it makes" should "land somewhere else" in
    assert(
      ContractAddress.of(creator, count(1L)).toHex == "894bcfd2eed71b2082101dc85f86865824efb62d",
      "the count is what stops one creator making the same address twice"
    )

  "a third" should "land somewhere else again" in
    assert(
      ContractAddress.of(creator, count(2L)).toHex == "8b9dd4a5606fa552b2fedaa3f515f79adb8e3f6f",
      "each count names its own address, which is what makes a creation predictable before it runs"
    )

  "a different creator at the same count" should "land somewhere else" in
    assert(
      ContractAddress.of(EvmFixtures.address(0x33), count(0L)) != ContractAddress.of(creator, count(0L)),
      "the creator is half the input, so two accounts cannot collide by both starting fresh"
    )
