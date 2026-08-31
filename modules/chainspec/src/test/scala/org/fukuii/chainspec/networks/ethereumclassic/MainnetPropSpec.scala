package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, UpgradeId, UpgradeRules, UpgradeSchedule}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** The activation figures, and what each one resolves to on either side of
  * itself. Structural facts about the schedule live in
  * [[MainnetSpec]].
  *
  * ==Two tables rather than one, because they fail on different mistakes==
  *
  * The first pins the numbers. The second pins what they do. An activation that
  * gates no rule -- this network has two, one of which never happens -- moves
  * without perturbing any resolution, so the second table alone cannot see it
  * move; a resolution read from the wrong rule set at a correct block is
  * invisible to the first.
  */
class MainnetPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val schedule: UpgradeSchedule =
    Mainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def named(entry: UpgradeSchedule.Entry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  /** Every figure here is external and separately cited on the entry that
    * carries it. None is derivable from anything in this repository.
    *
    * The upgrade the two networks parted over is not among them: this network
    * declined it, so it is not on the schedule at all. `SharedHistorySpec` is
    * where that block is stated and sourced.
    */
  private val activations = Table(
    ("upgrade", "block"),
    ("Frontier", 0L),
    ("Frontier Thawing", 200000L),
    ("Homestead", 1150000L),
    ("Gas Reprice", 2500000L),
    ("Die Hard", 3000000L),
    ("Gotham", 5000000L),
    ("Defuse Difficulty Bomb", 5900000L),
    ("Atlantis", 8772000L),
    ("Agharta", 9573000L)
  )

  /** The last height under the old rules and the first under the new, per
    * boundary, so an activation moved by one is caught in one direction or the
    * other rather than only when it is moved far.
    *
    * The row at 2,462,999 and the one after it are this network's answer at the
    * other network's reprice block: still the earlier rules, for another 37,000
    * blocks. The pair at 2,674,999 and 2,675,000 is the same reading one
    * upgrade later, where the other network takes EIP-155 and EIP-160 and this
    * one does not reach them for another 325,000. Neither pair is a boundary
    * here; both are heights at which the other network moves and this one does
    * not, which is a fact only a table holding both networks' figures can
    * state.
    *
    * The pair at 8,771,999 and 8,772,000 is a boundary like the others: the
    * first under the rules reached by removing the bomb, the second under the
    * ten proposals that `params/config_classic.go` at `4185df450` places
    * together at that height.
    *
    * ==The last two rows are not a boundary, and they are here to refute one==
    *
    * 9,582,999 and 9,583,000 both resolve to the rules that activate ten
    * thousand blocks below them. That is the figure published ECIP-1066 gives
    * this network's Agharta row, which `Mainnet`'s entry for that upgrade cites
    * as wrong and sources against four readings that agree on 9,573,000. A
    * schedule built from the table's figure instead would resolve the first of
    * these two to the rules below, and nothing else in this build would report
    * it: the certification tiers that run this network's fixtures never straddle
    * either height.
    */
  private val boundaries = Table(
    ("height", "rules"),
    (0L, Upgrades.frontier),
    (199999L, Upgrades.frontier),
    (200000L, Upgrades.frontier),
    (1149999L, Upgrades.frontier),
    (1150000L, Upgrades.homestead),
    (1919999L, Upgrades.homestead),
    (1920000L, Upgrades.homestead),
    (2462999L, Upgrades.homestead),
    (2463000L, Upgrades.homestead),
    (2499999L, Upgrades.homestead),
    (2500000L, Upgrades.gasReprice),
    (2674999L, Upgrades.gasReprice),
    (2675000L, Upgrades.gasReprice),
    (2999999L, Upgrades.gasReprice),
    (3000000L, Upgrades.dieHard),
    (4999999L, Upgrades.dieHard),
    (5000000L, Upgrades.gotham),
    (5899999L, Upgrades.gotham),
    (5900000L, Upgrades.defuse),
    (8771999L, Upgrades.defuse),
    (8772000L, Upgrades.atlantis),
    (9572999L, Upgrades.atlantis),
    (9573000L, Upgrades.agharta),
    (9582999L, Upgrades.agharta),
    (9583000L, Upgrades.agharta)
  )

  property("every upgrade activates at the block its own citation states") {
    forAll(activations) { (upgrade: String, block: Long) =>
      val entry = schedule.entries.find(named(_) == upgrade).getOrElse(fail("no entry named " + upgrade))
      assert(
        entry.activation == Activation.AtBlock(UInt64.fromBits(block)),
        upgrade + " activates at " + entry.activation.toString + " rather than block " + block.toString
      )
    }
  }

  property("every height resolves to the rules in force at it") {
    forAll(boundaries) { (height: Long, rules: UpgradeRules) =>
      assert(
        schedule.at(UInt64.fromBits(height), UInt64.Zero) eq rules,
        "height " + height.toString + " resolved to rules other than the ones authored for it"
      )
    }
  }
