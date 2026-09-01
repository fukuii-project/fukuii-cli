package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.scalatest.flatspec.AnyFlatSpec

/** That adopting EIP-2718 records an adoption and changes no rule.
  *
  * ==A spec for a component whose delta is the identity, which is the only kind
  * of assertion available and is worth making==
  *
  * Every other proposal here is checked by what it moved. This one has nothing
  * to move: the envelope it defines is in force at every height this build
  * carries, because the two types that implement it branch on a transaction's
  * own format and never on a height. So what can go wrong is the opposite of the
  * usual failure -- a delta that changes something -- and that is what these
  * cases pin.
  *
  * **The four facets are compared by identity and not by equality**, which is
  * the stronger statement and the one that matches what the seam claims: a
  * component touching no facet leaves the same value rather than an equal copy.
  * A delta rebuilding a facet from its own fields would compare equal and would
  * mean this document had reached it.
  */
class Eip2718Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.muirGlacier

  private val adopted: UpgradeRules = base.adopting(Eip2718.component)

  "adopting EIP-2718" should "leave the machine's rules as the same value" in
    assert(adopted.evm eq base.evm, "a document defining an envelope reached the machine")

  it should "leave admission's rules as the same value" in
    // The one a reader expects this document to move, and it does not: which
    // formats a block may carry is settled per format by the document defining
    // that format, and this one defines none.
    assert(adopted.admission eq base.admission, "the envelope admits no format by itself")

  it should "leave settlement's rules as the same value" in
    assert(adopted.execution eq base.execution, "the receipt envelope is not a rule a fork resolves here")

  it should "leave the consensus rules as the same value" in
    assert(adopted.consensus eq base.consensus, "a transaction encoding is not a consensus rule")

  it should "admit no format it did not already admit" in
    // Stated separately from the identity above because it is the specific claim
    // a reader will want: adopting the envelope does not make a tagged
    // transaction valid anywhere.
    assert(
      adopted.admission.admittedTypes == base.admission.admittedTypes,
      "a format became valid on the strength of a document that defines no format"
    )

  it should "record itself in the component list" in
    // The whole of what this adoption does. The journal states what the network
    // adopted, and that these rules were already in the shape the document
    // requires is a fact about this build rather than about the network.
    assert(adopted.components.contains(ProposalId.Eip(2718)), "the journal must record what was adopted")

  it should "record itself exactly once and after everything already there" in
    assert(
      adopted.components == base.components :+ ProposalId.Eip(2718),
      "an adoption that changes no rule still has a place in the order"
    )
