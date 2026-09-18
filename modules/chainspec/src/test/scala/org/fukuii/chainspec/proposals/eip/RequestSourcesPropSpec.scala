package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{Component, ProposalId, UpgradeRules}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** The three documents that fill EIP-7685's container, whose rule-set delta is
  * empty and whose adoption is recorded anyway.
  *
  * ==One table because the claim is identical for all three==
  *
  * Each adds a source of request records rather than a rule a fork switches: the
  * container's own header flag decides whether a block assembles a list at all,
  * the two call targets are fixed by their documents, and the deposit contract's
  * address is the network's. So the assertion is the same for each, and three
  * near-identical classes would differ only in a literal.
  *
  * ==What this is actually guarding==
  *
  * An empty delta is indistinguishable from a forgotten one by reading the
  * source. These cases state that the emptiness is deliberate: the record moves
  * and no rule does. **If a later document attaches a delta to one of these
  * components, the row that fails names which** -- which is the failure a
  * per-proposal spec asserting only its own presence would miss.
  */
class RequestSourcesPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val sources =
    Table(
      ("document", "component"),
      ("EIP-6110, deposits read from receipts", Eip6110.component),
      ("EIP-7002, triggerable withdrawals", Eip7002.component),
      ("EIP-7251, consolidations", Eip7251.component)
    )

  property("adopting a request source records it among the components adopted") {
    forAll(sources) { (document: String, component: Component) =>
      assert(
        base.adopting(component).components == base.components :+ component.id,
        document + " is recorded, in the position it was adopted in"
      )
    }
  }

  property("adopting a request source changes no rule") {
    forAll(sources) { (document: String, component: Component) =>
      // The whole record compared, not a facet at a time: a delta reaching any
      // facet fails here, including one reaching a facet this file does not
      // know to name.
      assert(
        base.adopting(component).copy(components = base.components) == base,
        document + " adds a source of records, and no rule a fork switches"
      )
    }
  }

  property("a request source is not the container, and does not turn one on") {
    forAll(sources) { (document: String, component: Component) =>
      // The distinction that decides a header value. Adopting a source without
      // the container must leave a block committing to nothing -- otherwise a
      // fork could be assembled that assembles a list its headers cannot state.
      assert(
        !base.adopting(component).header.carriesRequestsHash,
        document + " supplies records for a container it does not itself provide"
      )
    }
  }

  property("each names its own document") {
    forAll(sources) { (document: String, component: Component) =>
      assert(
        component.id match
          case ProposalId.Eip(number) => Seq(6110, 7002, 7251).contains(number)
          case _                      => false,
        document + " is recorded under its own proposal series and number"
      )
    }
  }
