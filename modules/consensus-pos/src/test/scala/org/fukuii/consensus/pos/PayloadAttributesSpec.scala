package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

class PayloadAttributesSpec extends AnyFlatSpec:

  private val bare = PosFixtures.bareAttributes
  private val withWithdrawals = PosFixtures.attributesWithWithdrawals
  private val withBeaconRoot = PosFixtures.attributesWithBeaconRoot

  "PayloadAttributes.structureVersion" should "be 1 when nothing is appended" in
    assert(bare.structureVersion == 1, "the three mandatory fields alone are a PayloadAttributesV1")

  it should "be 2 when the withdrawal list is appended" in
    assert(withWithdrawals.structureVersion == 2, "one appended link is a PayloadAttributesV2")

  it should "be 3 when the parent beacon root is appended" in
    assert(withBeaconRoot.structureVersion == 3, "two appended links are a PayloadAttributesV3")

  "PayloadAttributes.withdrawals" should "be absent at structure version 1" in
    assert(bare.withdrawals.isEmpty, "a build request from before EIP-4895 carries no list")

  it should "carry the appended list once the link is present" in
    assert(withWithdrawals.withdrawals.contains(Seq(PosFixtures.withdrawal)), "the list is read off the link")

  "PayloadAttributes.parentBeaconBlockRoot" should "be absent when only the withdrawal link is appended" in
    assert(withWithdrawals.parentBeaconBlockRoot.isEmpty, "EIP-4788's field arrives one link later")

  it should "carry the appended value once the link is present" in
    assert(withBeaconRoot.parentBeaconBlockRoot.contains(PosFixtures.hash(0x99)), "read off the second link")

  "PayloadAttributes.familyFields" should "be absent by default" in
    assert(bare.familyFields.isEmpty, "a build request on a family that appends nothing carries nothing here")

  it should "carry a family's own value through the core untouched" in {
    val extended = bare.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(11)))
    assert(
      extended.familyFields.contains(PosFixtures.SampleFamilyAttributeFields(11)),
      "the slot returns what a family put in it"
    )
  }

  it should "leave every core field untouched when a family value is present" in {
    val extended = bare.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(11)))
    assert(
      extended.copy(familyFields = None) == bare,
      "an appended family value changes nothing the core reads"
    )
  }

  "PayloadAttributes" should "distinguish two requests differing only in an appended link" in
    assert(withWithdrawals != withBeaconRoot, "the structure version is part of what a build request is")
