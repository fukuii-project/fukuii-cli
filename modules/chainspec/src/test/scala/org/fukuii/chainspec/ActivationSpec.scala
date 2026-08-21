package org.fukuii.chainspec

import org.fukuii.bytes.UInt64
import org.scalatest.flatspec.AnyFlatSpec

/** The named behaviors of an activation. The generated properties behind the
  * same comparison are in [[ActivationPropSpec]].
  */
class ActivationSpec extends AnyFlatSpec:

  private val order = Activation.activationOrdering

  private def number(value: Long): UInt64 = UInt64.fromLong(value).toOption.get

  /** A block number far larger than the timestamp it is compared against, so a
    * comparison that read the two figures rather than the axes would order them
    * the wrong way round.
    */
  private val lateBlock = Activation.AtBlock(number(21_000_000))

  private val earlyTimestamp = Activation.AtTimestamp(UInt64.Zero)

  "a block activation" should "precede a timestamp activation whatever the two figures are" in
    assert(
      order.tryCompare(lateBlock, earlyTimestamp).contains(-1),
      "EIP-6122 requires timestamp forks at or after block forks, so the axis decides and the figures do not"
    )

  "two activations on one axis" should "compare as unsigned quantities" in
    assert(
      order.tryCompare(Activation.AtBlock(UInt64.MaxValue), Activation.AtBlock(number(1))).exists(_ > 0),
      "the machine word is unsigned, and a signed reading puts everything above 2^63 below zero"
    )

  "an activation with no point" should "refuse comparison rather than degrade to one axis" in
    assert(
      order.tryCompare(Activation.Unscheduled, lateBlock).isEmpty,
      "an unscheduled upgrade is not before or after anything, and answering a number would invent a position"
    )

  "an upgrade a network will never take" should "not be equal to one it has not scheduled" in
    assert(
      Activation.Never != Activation.Unscheduled,
      "a permanent refusal and a pending activation are different facts, and no client in the corpus can say so"
    )

  it should "still refuse comparison against one it has not scheduled" in
    assert(
      order.tryCompare(Activation.Never, Activation.Unscheduled).isEmpty,
      "distinguishable is not the same as ordered"
    )

  "an activation with no point" should "compare equal to itself" in
    assert(
      order.tryCompare(Activation.Unscheduled, Activation.Unscheduled).contains(0),
      "a partial ordering that cannot place a value beside itself is not reflexive"
    )

  "the collapsed point" should "be the same quantity on either axis" in
    assert(
      Activation.AtBlock(number(5)).point == Some(number(5)) &&
        Activation.AtTimestamp(number(5)).point == Some(number(5)),
      "the fork identifier hashes one unsigned 64-bit figure per activation, whichever axis it came from"
    )

  it should "be absent where the upgrade activates nowhere" in
    assert(
      Activation.Unscheduled.point.isEmpty && Activation.Never.point.isEmpty,
      "an activation that never happens contributes nothing to a fork identifier"
    )

  "the axis" should "name which quantity a scheduled activation is measured in" in
    assert(
      Activation.AtBlock(number(5)).axis.contains(Activation.Axis.Block) &&
        Activation.AtTimestamp(number(5)).axis.contains(Activation.Axis.Timestamp),
      "the axis is part of the value, which is what keeps the comparison from reading the figure alone"
    )

  it should "be absent where the upgrade activates nowhere" in
    assert(
      Activation.Unscheduled.axis.isEmpty && Activation.Never.axis.isEmpty,
      "neither is measured in anything"
    )
