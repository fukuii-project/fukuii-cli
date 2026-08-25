package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Upgrade, UpgradeId, UpgradeSchedule}
import org.scalatest.flatspec.AnyFlatSpec

/** What this network's schedule is made of, and the two entries that gate no
  * rule while differing about everything else.
  *
  * ==The pair is the reason the schedule's upgrade type has three cases==
  *
  * Neither Frontier Thawing nor the DAO fork changes a rule this build models,
  * so nothing in [[MainnetPropSpec]]'s resolution table can tell them apart.
  * They separate at the fork identifier: one of them is a point at which two
  * nodes can disagree about validity and the other is not. Asserting both
  * directions here is what stops a projection keyed on *"does this change the
  * rules"* passing, which is the natural guess and is wrong on both entries at
  * once.
  *
  * The activation figures themselves are a matrix and live in
  * [[MainnetPropSpec]]; this holds the structural facts.
  */
class MainnetSpec extends AnyFlatSpec:

  private val schedule: UpgradeSchedule =
    Mainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def label(entry: org.fukuii.chainspec.UpgradeSchedule.Entry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  private def entryNamed(text: String): org.fukuii.chainspec.UpgradeSchedule.Entry =
    schedule.entries.find(entry => label(entry) == text).getOrElse(fail("no entry named " + text))

  private val thawing: org.fukuii.chainspec.UpgradeSchedule.Entry = entryNamed("Frontier Thawing")

  private val daoFork: org.fukuii.chainspec.UpgradeSchedule.Entry = entryNamed("DAO Fork")

  "the authored entries" should "form a schedule" in
    assert(
      Mainnet.schedule.isRight,
      "every construction invariant is checked here, so a Left is this file disagreeing with itself"
    )

  "the schedule" should "carry this network's canonical enumeration in order" in
    assert(
      schedule.entries.map(label) ==
        Vector("Frontier", "Frontier Thawing", "Homestead", "DAO Fork", "Tangerine Whistle", "Spurious Dragon"),
      "an enumeration missing an entry misnumbers every entry after it, which is silent rather than absent"
    )

  it should "belong to chain id 1" in
    assert(
      schedule.network.chainId == UInt64.fromBits(1L),
      "EIP-155 registers 1 to Ethereum mainnet, and a schedule filed under another id answers every lookup wrongly"
    )

  "Frontier Thawing" should "enforce nothing" in
    assert(
      thawing.upgrade == Upgrade.Unenforced,
      "it changed the block gas limit, which a miner already set per block within bounds it did not alter"
    )

  it should "be on the schedule even so" in
    assert(
      thawing.activation == Activation.AtBlock(UInt64.fromBits(200000L)),
      "both canonical enumerations in the field carry it, and omitting it renumbers everything after it"
    )

  it should "not reach the fork identifier" in
    assert(
      !schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(200000L))),
      "a fork identifier computed over it would be wrong on every peer handshake, and wrong quietly"
    )

  "the DAO fork" should "change no rule this build models" in
    assert(
      daoFork.upgrade == Upgrade.IrregularStateChange,
      "it moved balances and required a header marker, and neither is a machine, settlement or admission rule"
    )

  it should "reach the fork identifier even so" in
    // The half that separates it from Frontier Thawing, and the reason the
    // upgrade type cannot be two-valued. EIP-2124's worked example for this
    // network runs genesis, 1150000, 1920000 -- the third of those is here.
    assert(
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(1920000L))),
      "a fork identifier omitting it is a number every peer computes differently, and rejects quietly"
    )

  it should "leave the rules on both sides of it identical" in
    // What makes the entry an event rather than a rule change, asserted on the
    // schedule rather than inferred from the case it carries.
    assert(
      schedule.at(UInt64.fromBits(1919999L), UInt64.Zero) eq schedule.at(UInt64.fromBits(1920000L), UInt64.Zero),
      "an upgrade that resolves to different rules across itself is a rule change, whatever case it was given"
    )

  "this network's fork points" should "be exactly the upgrades that change what a node validates" in
    assert(
      schedule.forkPoints == Vector(
        Activation.AtBlock(UInt64.fromBits(1150000L)),
        Activation.AtBlock(UInt64.fromBits(1920000L)),
        Activation.AtBlock(UInt64.fromBits(2463000L)),
        Activation.AtBlock(UInt64.fromBits(2675000L))
      ),
      "genesis is excluded by EIP-2124 and thawing by enforcing nothing, leaving the ones that are neither"
    )
