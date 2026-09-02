package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, Upgrade, UpgradeId, UpgradeSchedule}
import org.scalatest.flatspec.AnyFlatSpec

/** What this network's schedule is made of, and the entries that must be in it
  * without reaching anything derived from them.
  *
  * The activation figures themselves are a matrix and live in
  * [[MainnetPropSpec]]; this holds the structural facts.
  */
class MainnetSpec extends AnyFlatSpec:

  private val schedule: UpgradeSchedule =
    Mainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def label(entry: UpgradeSchedule.Entry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  private def entryNamed(text: String): UpgradeSchedule.Entry =
    schedule.entries.find(entry => label(entry) == text).getOrElse(fail("no entry named " + text))

  private val thawing: UpgradeSchedule.Entry = entryNamed("Frontier Thawing")

  private val mess: UpgradeSchedule.Entry = entryNamed("MESS")

  "the authored entries" should "form a schedule" in
    assert(
      Mainnet.schedule.isRight,
      "every construction invariant is checked here, so a Left is this file disagreeing with itself"
    )

  "the schedule" should "carry this network's canonical enumeration in order" in
    assert(
      schedule.entries.map(label) ==
        Vector(
          "Frontier",
          "Frontier Thawing",
          "Homestead",
          "Gas Reprice",
          "Die Hard",
          "Gotham",
          "Defuse Difficulty Bomb",
          "Atlantis",
          "Agharta",
          "Phoenix",
          "MESS",
          "Thanos",
          "Magneto"
        ),
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

  "MESS" should "enforce nothing" in
    assert(
      mess.upgrade == Upgrade.Unenforced,
      "ECBP-1100 selects between chains that are each already valid, so no node validates differently for it"
    )

  it should "be on the schedule even so" in
    assert(
      mess.activation == Activation.AtBlock(UInt64.fromBits(11380000L)),
      "the network scheduled and named it at this height, and what a client does with it is not the schedule's"
    )

  it should "not be a fork point, unlike every entry the network expects nodes to diverge across" in
    // The pair that distinguishes this case from Gotham below. Gotham moves no
    // value a rule set holds and IS a fork point; this moves none and is not.
    // What separates them is not the size of the change but whether a node can
    // validate differently across it.
    assert(
      !schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(11380000L))),
      "a recommendation a client may decline cannot be a height the identifier is a checksum over"
    )

  "this network's fork points" should "be exactly the upgrades that change what a node validates" in
    assert(
      schedule.forkPoints ==
        Vector(
          Activation.AtBlock(UInt64.fromBits(1150000L)),
          Activation.AtBlock(UInt64.fromBits(2500000L)),
          Activation.AtBlock(UInt64.fromBits(3000000L)),
          Activation.AtBlock(UInt64.fromBits(5000000L)),
          Activation.AtBlock(UInt64.fromBits(5900000L)),
          Activation.AtBlock(UInt64.fromBits(8772000L)),
          Activation.AtBlock(UInt64.fromBits(9573000L)),
          Activation.AtBlock(UInt64.fromBits(10500839L)),
          Activation.AtBlock(UInt64.fromBits(11700000L)),
          Activation.AtBlock(UInt64.fromBits(13189133L))
        ),
      "genesis is excluded by EIP-2124 and the two unenforced entries by enforcing nothing, leaving the rest"
    )

  "Gotham" should "be a fork point even though it moves no value a rule set holds" in
    // The one entry so far whose rule change is entirely outside the rule set:
    // both its components leave every facet as it was, and the emission it steps
    // down is computed by the engine. Recording it as enforcing nothing would
    // drop it from forkPoints, and the network states the opposite at the peer
    // layer -- 5,000,000 is one of the blocks its EIP-2124 identifier is a
    // checksum over, where 200,000 is not.
    assert(
      entryNamed("Gotham").upgrade == Upgrade.RuleChange(Upgrades.gotham) &&
        schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(5000000L))),
      "the height the emission steps down at is not one this schedule expects nodes to diverge across"
    )
