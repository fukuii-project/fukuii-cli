package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.fukuii.evm.SelfDestructScope
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-6780 changes, and what it must leave alone.
  *
  * ==The delta is one member, which is what makes the negatives the work==
  *
  * No operation joins or leaves the table and no figure moves, so almost
  * everything asserted here is something the document does NOT touch. The
  * document says both of those in terms -- *"the rules of EIP-2929 regarding
  * `SELFDESTRUCT` remain unchanged"*, and the refund it mentions is EIP-3529's,
  * which is a fork below (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-09),
  * `EIPS/eip-6780.md`, Final).
  */
class Eip6780Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.shanghai

  private val adopted: UpgradeRules = base.adopting(Eip6780.component)

  "adopting EIP-6780" should "narrow which accounts a destruction removes" in
    assert(
      adopted.evm.selfDestructScope == SelfDestructScope.AccountsCreatedInTransaction,
      "the document's whole delta: SELFDESTRUCT deletes nothing where the account predates the transaction"
    )

  it should "not have been narrowed before it was adopted" in
    assert(
      base.evm.selfDestructScope == SelfDestructScope.AnyAccount,
      "the fork below already carried it, leaving the change untested"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(6780)),
      "the journal states which document produced these rules"
    )

  it should "put no operation in the table and take none out" in
    assert(
      adopted.evm.table == base.evm.table,
      "the operation stays at its byte with its stack and its price unchanged, so the table is the same value"
    )

  it should "move no price at all" in
    assert(
      adopted.evm.schedule == base.evm.schedule,
      "the document leaves EIP-2929's rules for this operation alone, and the refund it names is a fork below's"
    )

  it should "be the whole of the machine's delta" in
    // The one member against every other member of the same facet, so a
    // component that changed a second thing would fail here rather than in
    // whichever spec happened to read that thing next.
    assert(
      adopted.evm == base.evm.copy(selfDestructScope = SelfDestructScope.AccountsCreatedInTransaction),
      "one member in one facet is the whole delta"
    )

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.admission, adopted.consensus, adopted.header) ==
        (base.execution, base.admission, base.consensus, base.header),
      "nothing about admission, execution, consensus or the header changes"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The rule reads a record of what the transaction created and nothing a
    // network's consensus mechanism supplies, so there is no value a
    // proof-of-work network would lack. Adopting it there is a configuration
    // decision no network in this project's series has taken, and the component
    // composing is a different claim from a network wanting it.
    val powBase = ethereumclassic.Upgrades.spiral
    val powAdopted = powBase.adopting(Eip6780.component)
    assert(
      powAdopted.evm.selfDestructScope == SelfDestructScope.AccountsCreatedInTransaction &&
        powBase.evm.selfDestructScope == SelfDestructScope.AnyAccount,
      "the member lands on a proof-of-work rule set exactly as it lands on this one"
    )
  }
