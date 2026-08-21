package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Upgrade, UpgradeId, UpgradeSchedule}
import org.scalatest.flatspec.AnyFlatSpec

/** What this network's schedule is made of, and the one entry that must be in
  * it without reaching anything derived from it.
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

  private val thawing: org.fukuii.chainspec.UpgradeSchedule.Entry =
    schedule.entries.find(entry => label(entry) == "Frontier Thawing").getOrElse(fail("no thawing entry"))

  "the authored entries" should "form a schedule" in
    assert(
      Mainnet.schedule.isRight,
      "every construction invariant is checked here, so a Left is this file disagreeing with itself"
    )

  "the schedule" should "carry this network's canonical enumeration in order" in
    assert(
      schedule.entries.map(label) == Vector("Frontier", "Frontier Thawing", "Homestead", "Tangerine Whistle"),
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

  "this network's fork points" should "be exactly the two upgrades that change what a node validates" in
    assert(
      schedule.forkPoints ==
        Vector(Activation.AtBlock(UInt64.fromBits(1150000L)), Activation.AtBlock(UInt64.fromBits(2463000L))),
      "genesis is excluded by EIP-2124 and thawing by enforcing nothing, leaving the two that are neither"
    )
