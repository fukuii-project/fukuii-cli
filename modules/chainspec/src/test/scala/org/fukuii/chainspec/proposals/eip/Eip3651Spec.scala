package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3651 changes, and the much larger set of things it must
  * leave alone.
  *
  * ==The negative cases carry more weight than the positive one==
  *
  * The delta is one assignment, so asserting that it happened is cheap. The
  * document's own wording invites two wrong deltas that a spec checking only
  * the new member would accept: a repricing, because its abstract mentions
  * *"the actual cost of reading that account"*, and a change to the charge every
  * transaction pays, because the address joins a set whose name it shares with
  * the transaction's own declaration. Both are named below.
  *
  * **What the rule actually costs is asserted where it can be**, in
  * `org.fukuii.execution.WarmBeneficiarySpec`, which runs a reach at the
  * beneficiary under each answer and compares the gas. Nothing here can: a rule
  * set is a value, and the price a reach pays is settled where a transaction is.
  */
class Eip3651Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.paris

  private val adopted: UpgradeRules = base.adopting(Eip3651.component)

  "adopting EIP-3651" should "put the block's beneficiary in the set seeded before the first invocation" in
    assert(adopted.evm.coinbaseStartsWarm, "the document's whole specification is that this address starts warm")

  it should "not have been there before it was adopted" in
    // Without this the case above is satisfied by a build that warms the
    // beneficiary at every height, which is a divergence three forks deep.
    assert(
      !base.evm.coinbaseStartsWarm,
      "the fork below already warmed it, leaving the change untested"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(3651)),
      "the journal states which document produced these rules"
    )

  it should "move no price at all" in
    // "in accordance with the actual cost of reading that account" is the
    // document explaining why the address belongs in the set, not a repricing.
    // A delta that moved the warm or cold figure would satisfy the member
    // assertion above and change every reach on the network.
    assert(adopted.evm.schedule == base.evm.schedule, "no figure moves, and the two schedules are the same value")

  it should "leave the machine's operations exactly as they were" in
    assert(adopted.evm.table == base.evm.table, "no operation is added, removed or repriced")

  it should "leave what admits a transaction alone" in
    // The near miss this case exists for: the address joins the set that seeds
    // the warm addresses, and the transaction's own declaration seeds the same
    // set. A delta reaching the admission facet would be charging every
    // transaction for an address it never declared.
    assert(adopted.admission == base.admission, "the document settles nothing about admitting a transaction")

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.consensus, adopted.header) == (base.execution, base.consensus, base.header),
      "one member of one facet is the whole delta"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The document needs no beacon chain and no withdrawals: it names one
    // address that every block already has. So a network running proof of work
    // adopts it over its own tip, with the same one-member delta -- which is
    // what keeps it separable from the rest of the upgrade that shipped it.
    val powBase = ethereumclassic.Upgrades.mystique
    val powAdopted = powBase.adopting(Eip3651.component)
    assert(
      powAdopted.evm.coinbaseStartsWarm && !powBase.evm.coinbaseStartsWarm,
      "the delta lands on a proof-of-work rule set exactly as it lands on this one"
    )
  }
