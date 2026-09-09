package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64

/** What the appended chain buys, stated as behavior rather than as shape.
  *
  * The chain's whole claim is that the structure version is a property of the
  * value and that the undefined combinations do not exist. The first half is
  * asserted here; the second is asserted by the compiler, since there is no
  * constructor that reaches them.
  */
class ExecutionPayloadSpec extends AnyFlatSpec:

  private val core = PosFixtures.bareCore
  private val withWithdrawals = PosFixtures.withWithdrawals
  private val withBlobGas = PosFixtures.withBlobGas

  "ExecutionPayload.structureVersion" should "be 1 when nothing is appended" in
    assert(core.structureVersion == 1, "the fourteen mandatory fields alone are an ExecutionPayloadV1")

  it should "be 2 when the withdrawal list is appended" in
    assert(withWithdrawals.structureVersion == 2, "one appended link is an ExecutionPayloadV2")

  it should "be 3 when the blob-gas pair is appended" in
    assert(withBlobGas.structureVersion == 3, "two appended links are an ExecutionPayloadV3")

  "ExecutionPayload.withdrawals" should "be absent at structure version 1" in
    assert(core.withdrawals.isEmpty, "a payload from before EIP-4895 carries no list, not an empty one")

  it should "carry the appended list once the link is present" in
    assert(withWithdrawals.withdrawals.contains(Seq(PosFixtures.withdrawal)), "the list is read off the link")

  it should "still be present when a further link follows it" in
    assert(withBlobGas.withdrawals.contains(Seq(PosFixtures.withdrawal)), "the chain is a prefix, so V3 includes V2")

  "ExecutionPayload.blobGasUsed" should "be absent when only the withdrawal link is appended" in
    assert(withWithdrawals.blobGasUsed.isEmpty, "EIP-4844's fields arrive one link later than EIP-4895's")

  it should "carry the appended value once the link is present" in
    assert(withBlobGas.blobGasUsed.contains(UInt64.fromBits(131072L)), "the value is read off the second link")

  "ExecutionPayload.excessBlobGas" should "carry the appended value once the link is present" in
    assert(withBlobGas.excessBlobGas.contains(UInt64.fromBits(262144L)), "the pair arrives together")

  "ExecutionPayload.familyFields" should "be absent by default" in
    assert(core.familyFields.isEmpty, "a payload on a network family that appends nothing carries nothing here")

  it should "carry a family's own value through the core untouched" in {
    val extended = core.copy(familyFields = Some(PosFixtures.SampleFamilyPayloadFields(7)))
    assert(
      extended.familyFields.contains(PosFixtures.SampleFamilyPayloadFields(7)),
      "the slot returns what a family put in it, which is what a driver reading it back needs"
    )
  }

  it should "leave every core field untouched when a family value is present" in {
    val extended = core.copy(familyFields = Some(PosFixtures.SampleFamilyPayloadFields(7)))
    assert(
      extended.copy(familyFields = None) == core,
      "an appended family value changes nothing the core reads, which is what byte-identical means here"
    )
  }

  "ExecutionPayload" should "be equal by value" in
    assert(withBlobGas == PosFixtures.withBlobGas, "two payloads built from the same fields are the same payload")

  it should "distinguish two payloads differing only in an appended link" in
    assert(withWithdrawals != withBlobGas, "the structure version is part of what a payload is")
