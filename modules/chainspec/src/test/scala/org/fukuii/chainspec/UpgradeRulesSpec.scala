package org.fukuii.chainspec

import org.fukuii.evm.Proposal
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting a component does, and what it must not be able to do. */
class UpgradeRulesSpec extends AnyFlatSpec:

  import ChainspecFixtures.firstRules

  private val chargesMore: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionCreate = BigInt(7)))

  private val chargesLess: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionCreate = BigInt(3)))

  private val first = Component.evm(ProposalId.Eip(2), chargesMore)

  private val second = Component.evm(ProposalId.Ecip(1017), chargesLess)

  /** A component whose delta rewrites the record of what produced these rules,
    * which is the one thing the fold must not let it do.
    */
  private val forgesTheRecord =
    Component(ProposalId.Eip(1), rules => rules.copy(components = Vector(ProposalId.Eip(9999))))

  "adopting components" should "record their proposals in the order adopted" in
    assert(
      firstRules.adopting(first, second).components == Vector(ProposalId.Eip(2), ProposalId.Ecip(1017)),
      "the component list is what determines the rules, so it has to say which ones and in what order"
    )

  it should "apply each change to the facet it names" in
    assert(
      firstRules.adopting(first).evm.schedule.transactionCreate == BigInt(7),
      "a component that records its identity without changing anything would be a label, which is the thing this is not"
    )

  it should "compose in the order given where two touch one price" in
    assert(
      firstRules.adopting(first, second).evm.schedule.transactionCreate == BigInt(3),
      "order is the caller's to state, and the later component is the one in force"
    )

  "adopting nothing" should "leave every facet as the same value" in
    assert(
      firstRules.adopting().evm eq firstRules.evm,
      "a facet no component names survives as the same value rather than as an equal copy"
    )

  "a component" should "not be able to rewrite what is recorded as producing the rules" in
    assert(
      firstRules.adopting(forgesTheRecord).components == Vector(ProposalId.Eip(1)),
      "the record is rebuilt from the components adopted, so a delta cannot edit its own provenance"
    )

  "a component built for the machine" should "reach no other facet" in
    assert(
      second.delta(firstRules.adopting(first)).components == Vector(ProposalId.Eip(2)),
      "the constructor names the one facet it writes, which is what keeps a machine change from touching a consensus rule"
    )
