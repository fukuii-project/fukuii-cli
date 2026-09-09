package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Hash

class PayloadStatusSpec extends AnyFlatSpec:

  private val zero: Hash = Hash.fromBytesTruncating(IArray.empty)
  private val block: Hash = PosFixtures.hash(0x0a)

  private def hashOf(status: PayloadStatus): Option[Hash] = status match
    case PayloadStatus.Valid(h)      => Some(h)
    case PayloadStatus.Invalid(h, _) => h
    case _                           => None

  "PayloadStatus.Valid" should "carry the block the status is about" in
    assert(hashOf(PayloadStatus.Valid(block)).contains(block), "a VALID response always names a block")

  "PayloadStatus.Invalid" should "distinguish a zero latest-valid hash from an absent one" in
    assert(
      PayloadStatus.Invalid(Some(zero), None) != PayloadStatus.Invalid(None, None),
      "a zero hash means the nearest valid ancestor is a proof-of-work block; absent means it is unknown"
    )

  it should "admit an absent validation error" in
    assert(
      PayloadStatus.Invalid(Some(block), None).validationError.isEmpty,
      "supplying detail is a MAY, so its absence is a legitimate response rather than a gap"
    )

  it should "carry a validation error where one is supplied" in
    assert(
      PayloadStatus.Invalid(None, Some("bad state root")).validationError.contains("bad state root"),
      "the message is part of the response and is not derived from the status"
    )

  "PayloadStatus.Syncing" should "carry no latest-valid hash" in
    assert(hashOf(PayloadStatus.Syncing).isEmpty, "no response in the specification pairs SYNCING with a hash")

  "PayloadStatus.Accepted" should "carry no latest-valid hash" in
    assert(hashOf(PayloadStatus.Accepted).isEmpty, "no response in the specification pairs ACCEPTED with a hash")

  "PayloadStatus.InvalidBlockHash" should "carry no latest-valid hash" in
    assert(
      hashOf(PayloadStatus.InvalidBlockHash(Some("hash mismatch"))).isEmpty,
      "the payload failed before any ancestor question could be asked"
    )

  "PayloadStatus" should "be equal by value" in
    assert(
      PayloadStatus.Invalid(Some(block), Some("why")) == PayloadStatus.Invalid(Some(block), Some("why")),
      "two responses stating the same thing are the same response"
    )

  it should "distinguish two statuses that differ only in their case" in
    assert(PayloadStatus.Syncing != PayloadStatus.Accepted, "the two mean different things to a consensus layer")
