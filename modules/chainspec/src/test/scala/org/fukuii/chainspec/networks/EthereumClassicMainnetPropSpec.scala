package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, ProtocolSpec, Schedule, ScheduleEntry, UpgradeId}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** The activation figures, and what each one resolves to on either side of
  * itself. Structural facts about the schedule live in
  * [[EthereumClassicMainnetSpec]].
  *
  * ==Two tables rather than one, because they fail on different mistakes==
  *
  * The first pins the numbers. The second pins what they do. An activation that
  * gates no rule -- this network has two, one of which never happens -- moves
  * without perturbing any resolution, so the second table alone cannot see it
  * move; a resolution read from the wrong rule set at a correct block is
  * invisible to the first.
  */
class EthereumClassicMainnetPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val schedule: Schedule =
    EthereumClassicMainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def named(entry: ScheduleEntry): String = entry.id.label match
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
    ("Gas Reprice", 2500000L)
  )

  /** The last height under the old rules and the first under the new, per
    * boundary, so an activation moved by one is caught in one direction or the
    * other rather than only when it is moved far.
    *
    * The row at 2,462,999 and the one after it are this network's answer at the
    * other network's reprice block: still the earlier rules, for another 37,000
    * blocks.
    */
  private val boundaries = Table(
    ("height", "rules"),
    (0L, EthereumClassic.frontier),
    (199999L, EthereumClassic.frontier),
    (200000L, EthereumClassic.frontier),
    (1149999L, EthereumClassic.frontier),
    (1150000L, EthereumClassic.homestead),
    (1919999L, EthereumClassic.homestead),
    (1920000L, EthereumClassic.homestead),
    (2462999L, EthereumClassic.homestead),
    (2463000L, EthereumClassic.homestead),
    (2499999L, EthereumClassic.homestead),
    (2500000L, EthereumClassic.gasReprice)
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
    forAll(boundaries) { (height: Long, rules: ProtocolSpec) =>
      assert(
        schedule.at(UInt64.fromBits(height), UInt64.Zero) eq rules,
        "height " + height.toString + " resolved to rules other than the ones authored for it"
      )
    }
  }
