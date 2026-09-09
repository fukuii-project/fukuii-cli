package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.BytesError

class PayloadIdSpec extends AnyFlatSpec:

  private val eightZeros: IArray[Byte] = IArray.fill(PayloadId.Width)(0.toByte)

  private def idOf(bytes: IArray[Byte]): PayloadId = PayloadId.fromBytes(bytes).toOption.get

  "PayloadId.fromBytes" should "accept exactly eight bytes" in
    assert(PayloadId.fromBytes(eightZeros).isRight, "the specification types the identifier as eight bytes of DATA")

  it should "refuse a shorter input rather than left-padding it" in
    assert(
      PayloadId.fromBytes(IArray.fill(7)(0.toByte)) == Left(BytesError.BadWidth(PayloadId.Width, 7)),
      "padding a short identifier would invent an identifier the execution layer never issued"
    )

  it should "refuse a longer input rather than truncating it" in
    assert(
      PayloadId.fromBytes(IArray.fill(9)(0.toByte)) == Left(BytesError.BadWidth(PayloadId.Width, 9)),
      "truncating would silently name a different build process"
    )

  "PayloadId.toHex" should "keep leading zeros" in
    assert(
      idOf(eightZeros).toHex == "0" * 16,
      "DATA carries its leading zeros where QUANTITY drops them, so a zero-led identifier must not shorten"
    )

  "PayloadId.fromHex" should "round-trip a value through its own rendering" in {
    val original = idOf(IArray[Byte](0, 0, 0, 1, 2, 3, 4, 5))
    assert(PayloadId.fromHex(original.toHex).contains(original), "the rendering is what the consensus layer sends back")
  }

  "PayloadId" should "be equal by value" in
    assert(idOf(eightZeros) == idOf(eightZeros), "two identifiers over the same bytes name the same build process")

  it should "find a value-equal identifier used as a map key" in {
    val key = idOf(IArray[Byte](9, 8, 7, 6, 5, 4, 3, 2))
    assert(
      Map(key -> 1).get(idOf(IArray[Byte](9, 8, 7, 6, 5, 4, 3, 2))).contains(1),
      "a build cache is keyed by this, so reference equality would lose every entry"
    )
  }

  it should "distinguish two identifiers differing in one byte" in
    assert(
      idOf(IArray[Byte](0, 0, 0, 0, 0, 0, 0, 1)) != idOf(IArray[Byte](0, 0, 0, 0, 0, 0, 0, 2)),
      "every byte is part of the identifier, since nothing here reads any of them as a tag"
    )
