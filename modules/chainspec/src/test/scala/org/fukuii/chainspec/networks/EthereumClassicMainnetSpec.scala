package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Schedule, ScheduleEntry, Upgrade, UpgradeId}
import org.scalatest.flatspec.AnyFlatSpec

/** What this network's schedule is made of, and the two entries that must be in
  * it without reaching anything derived from them.
  *
  * The activation figures themselves are a matrix and live in
  * [[EthereumClassicMainnetPropSpec]]; this holds the structural facts.
  */
class EthereumClassicMainnetSpec extends AnyFlatSpec:

  private val schedule: Schedule =
    EthereumClassicMainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def label(entry: ScheduleEntry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  private def entryNamed(text: String): ScheduleEntry =
    schedule.entries.find(entry => label(entry) == text).getOrElse(fail("no entry named " + text))

  private val thawing: ScheduleEntry = entryNamed("Frontier Thawing")

  "the authored entries" should "form a schedule" in
    assert(
      EthereumClassicMainnet.schedule.isRight,
      "every construction invariant is checked here, so a Left is this file disagreeing with itself"
    )

  "the schedule" should "carry this network's canonical enumeration in order" in
    assert(
      schedule.entries.map(label) ==
        Vector("Frontier", "Frontier Thawing", "Homestead", "Gas Reprice"),
      "an enumeration missing an entry misnumbers every entry after it, which is silent rather than absent"
    )

  it should "belong to chain id 61" in
    assert(
      schedule.network.chainId == UInt64.fromBits(61L),
      "the registry gives 61 to this network, and a schedule filed under another id answers every lookup wrongly"
    )

  "Frontier Thawing" should "enforce nothing" in
    assert(
      thawing.upgrade == Upgrade.Unenforced,
      "it changed the block gas limit, which a miner already set per block within bounds it did not alter"
    )

  it should "be on the schedule even so" in
    assert(
      thawing.activation == Activation.AtBlock(UInt64.fromBits(200000L)),
      "this network inherited the entry with the history, and omitting it renumbers everything after it"
    )

  "this network's fork points" should "be exactly the two upgrades that change what a node validates" in
    assert(
      schedule.forkPoints ==
        Vector(Activation.AtBlock(UInt64.fromBits(1150000L)), Activation.AtBlock(UInt64.fromBits(2500000L))),
      "genesis is excluded by EIP-2124 and thawing by enforcing nothing, leaving the two that are neither"
    )
