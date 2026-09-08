package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-4895 changes, and the far larger part of the document that
  * a rule set cannot express at all.
  *
  * ==The delta is one boolean, and the negative cases are what say why==
  *
  * A reader coming from the document expects a proposal this size to move
  * something in the machine, in what admits a transaction, or in what settling
  * one does. It moves none of them: the credit is unconditional and
  * parameterless, so there is nothing for a network to resolve, and the
  * operation *"has no associated gas costs"*. The cases below assert each of
  * those absences, because a delta that reached one of those facets would
  * satisfy an assertion on the new member alone.
  *
  * **What the rule actually does is asserted where it can be** --
  * `org.fukuii.execution.WithdrawalsSpec` for the credit and the commitment,
  * against the published corpus, and `org.fukuii.consensus.HeaderValidatorSpec`
  * for the header the member gates. Nothing here can: a rule set is a value, and
  * a withdrawal is a change to state.
  */
class Eip4895Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.paris

  private val adopted: UpgradeRules = base.adopting(Eip4895.component)

  "adopting EIP-4895" should "require a header to commit to the block's withdrawals" in
    assert(adopted.header.carriesWithdrawalsRoot, "a header at this fork states a root over the withdrawals")

  it should "not have required one before it was adopted" in
    // Without this the case above is satisfied by a build that requires the
    // field at every height, which refuses every block this network ever
    // produced before the merge.
    assert(!base.header.carriesWithdrawalsRoot, "the fork below required no such field, leaving the change untested")

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(4895)),
      "the journal states which document produced these rules"
    )

  it should "leave the machine exactly as it was" in
    // A withdrawal is not observable from inside the machine: it adds no
    // operation, moves no price and changes no behavior an invocation can see.
    assert(adopted.evm == base.evm, "the document reaches the machine nowhere")

  it should "leave what admits a transaction alone" in
    // The near miss: a withdrawal is described in the document as syntactically
    // similar to a transaction, and it is admitted by nothing -- there is no
    // sender to check, no nonce to compare and no charge to afford.
    assert(adopted.admission == base.admission, "a withdrawal is not a transaction and is admitted by no rule")

  it should "leave what settling a transaction does alone" in
    assert(adopted.execution == base.execution, "the credit is not a transaction's, and no fork parameterizes it")

  it should "leave what a block owes its consensus mechanism alone" in
    // The other near miss: withdrawals arrive from the consensus layer, so a
    // reader can take them for the mechanism's business. They are not -- the
    // execution layer is told what to credit and credits it, and the mechanism
    // that produced the block decides nothing about it.
    assert(adopted.consensus == base.consensus, "what pushes the withdrawals is not what seals the block")

  it should "settle nothing else about a header" in
    assert(
      (adopted.header.feeMarket, adopted.header.constants) == (base.header.feeMarket, base.header.constants),
      "one member of one facet is the whole delta"
    )

  "a proof-of-work rule set" should "not be reachable through this document" in {
    // The separability claim from the other direction. Ethereum Classic adopts
    // the three proposals that shipped beside this one and records this one as
    // nil, so the case that matters is not that this composes over a
    // proof-of-work tip -- it is that the other three do WITHOUT it, which is
    // what a component holding all four would have made impossible.
    val powBase = ethereumclassic.Upgrades.mystique
    val powAdopted = powBase.adopting(Eip3651.component, Eip3855.component, Eip3860.component)
    assert(
      !powAdopted.header.carriesWithdrawalsRoot,
      "a network with no beacon chain takes the three and none of the withdrawals work"
    )
  }
