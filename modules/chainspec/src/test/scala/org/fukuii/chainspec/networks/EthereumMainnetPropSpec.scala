package org.fukuii.chainspec.networks

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, ProtocolSpec, Schedule, UpgradeId}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** The activation figures, and what each one resolves to on either side of
  * itself. Structural facts about the schedule live in [[EthereumMainnetSpec]].
  *
  * ==Two tables rather than one, because they fail on different mistakes==
  *
  * The first pins the numbers. The second pins what they do. An activation that
  * gates no rule -- this network has one -- moves without perturbing any
  * resolution, so the second table alone cannot see it move; a resolution read
  * from the wrong rule set at a correct block is invisible to the first.
  */
class EthereumMainnetPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val schedule: Schedule =
    EthereumMainnet.schedule.getOrElse(fail("the authored entries do not form a schedule"))

  private def named(entry: org.fukuii.chainspec.ScheduleEntry): String = entry.id.label match
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
    ("Tangerine Whistle", 2463000L)
  )

  /** The last height under the old rules and the first under the new, per
    * boundary, so an activation moved by one is caught in one direction or the
    * other rather than only when it is moved far.
    */
  private val boundaries = Table(
    ("height", "rules"),
    (0L, Ethereum.frontier),
    (199999L, Ethereum.frontier),
    (200000L, Ethereum.frontier),
    (1149999L, Ethereum.frontier),
    (1150000L, Ethereum.homestead),
    (2462999L, Ethereum.homestead),
    (2463000L, Ethereum.tangerineWhistle)
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
