package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-658 changes, and the three facets it must leave alone.
  *
  * ==Through [[Eip658.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the settlement, because these are the document's
  * claims==
  *
  * What a settlement does with the rule -- which of the two first fields a
  * receipt is built with, and that a transaction which reverted carries a
  * failure -- is `org.fukuii.execution.BlockProcessorSpec`'s. What a published
  * receipt says about it is the certification harness's.
  */
class Eip658Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip658.component)

  "adopting EIP-658" should "have a receipt state that its transaction succeeded" in
    // The document: "the intermediate state root is replaced by a status code,
    // 0 indicating failure ... and 1 indicating success".
    assert(
      adopted.execution.receiptCarriesStatus,
      "a receipt still carries the field the document replaces"
    )

  it should "have had a receipt carrying a root before it was adopted" in
    // The control. Without it the case above passes against rules that already
    // carried a status, and no earlier proposal sets the member.
    assert(
      !base.execution.receiptCarriesStatus,
      "the preceding rules already replaced the root a receipt carries"
    )

  it should "settle that one at settlement and nothing else" in
    // Stated as the whole facet rather than as one member, so a second member
    // reached by accident fails as loudly as this one failing to move.
    assert(
      adopted.execution == base.execution.copy(receiptCarriesStatus = true),
      "a document whose delta is one rule reached a second member of the facet it writes"
    )

  it should "leave the machine, what admits a transaction and what consensus settles as the same values" in
    // Identity rather than equality: a delta that rebuilt a facet from its own
    // members would be indistinguishable by value from one that never touched
    // it, and the machine's rules are the expensive one to rebuild by accident.
    assert(
      (adopted.evm eq base.evm) && (adopted.admission eq base.admission) && (adopted.consensus eq base.consensus),
      "a document confined to the settlement altered a facet it does not name"
    )

  it should "record the document it adopted after the ones already recorded" in
    assert(
      adopted.components == base.components :+ ProposalId.Eip(658),
      "adopting the document did not record it, or recorded something else with it"
    )
