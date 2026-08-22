package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, UpgradeId, UpgradeRules, UpgradeSchedule}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** The activation figures, and what each one resolves to on either side of
  * itself. Structural facts about the schedule live in [[MainnetSpec]].
  *
  * ==Two tables rather than one, because they fail on different mistakes==
  *
  * The first pins the numbers. The second pins what they do. An activation that
  * gates no rule -- this network has two, and [[MainnetSpec]] is where they are
  * told apart -- moves without perturbing any resolution, so the second table
  * alone cannot see it move; a resolution read from the wrong rule set at a
  * correct block is invisible to the first.
  */
class MainnetPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val schedule: UpgradeSchedule =
    Mainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def named(entry: org.fukuii.chainspec.UpgradeSchedule.Entry): String = entry.id.label match
    case UpgradeId.Label.Named(text) => text
    case UpgradeId.Label.Synthesized => "Synthesized"

  /** Every figure here is external and separately cited on the entry that
    * carries it. None is derivable from anything in this repository.
    */
  private val activations = Table(
    ("upgrade", "block"),
    ("Frontier", 0L),
    ("Frontier Thawing", 200000L),
    ("Homestead", 1150000L),
    ("DAO Fork", 1920000L),
    ("Tangerine Whistle", 2463000L)
  )

  /** The last height under the old rules and the first under the new, per
    * boundary, so an activation moved by one is caught in one direction or the
    * other rather than only when it is moved far.
    *
    * The pair at the DAO fork is the opposite assertion to the rest of this
    * table: both sides resolve to the same rule set, because that upgrade
    * changes none. A row here that differed across it would mean the entry had
    * been given the wrong case.
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
    (2463000L, Upgrades.tangerineWhistle)
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
