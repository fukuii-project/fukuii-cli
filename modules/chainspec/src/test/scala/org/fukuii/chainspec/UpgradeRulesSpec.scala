package org.fukuii.chainspec

import org.fukuii.evm.Proposal
import org.fukuii.execution.{AdmissionRules, ExecutionRules}
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

  /** One proposal turning a rule on, and the same proposal turning it back off.
    *
    * A production chain reaches exactly this state -- `gnosischain/configs` @
    * `e542d13234` carries `eip1283Transition`, `eip1283DisableTransition` and
    * `eip1283ReenableTransition` at three ascending blocks -- so what these two
    * pin is not a hypothetical.
    */
  private val bounds =
    Component(
      ProposalId.Eip(1283),
      rules => rules.copy(admission = rules.admission.copy(signatureSMustBeLow = true))
    )

  private val unbounds =
    Component(
      ProposalId.Eip(1283),
      rules => rules.copy(admission = rules.admission.copy(signatureSMustBeLow = false))
    )

  "adopting components" should "record their proposals in the order adopted" in
    assert(
      firstRules.adopting(first, second).components == Vector(ProposalId.Eip(2), ProposalId.Ecip(1017)),
      "the record states which proposals were adopted and in what order, so both have to survive the fold"
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
      (firstRules.adopting().evm eq firstRules.evm) &&
        (firstRules.adopting().execution eq firstRules.execution) &&
        (firstRules.adopting().admission eq firstRules.admission),
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

  it should "leave the settlement and admission facets as the same values" in
    // The two facets added after that constructor was written. It cannot reach
    // them by construction, and this is what would notice if it ever could.
    assert(
      (firstRules.adopting(first).execution eq firstRules.execution) &&
        (firstRules.adopting(first).admission eq firstRules.admission),
      "a component built for the machine rebuilt a facet outside it"
    )

  "a component that reverses an earlier one" should "be recorded beside the one it reverses" in
    assert(
      firstRules.adopting(bounds, unbounds).components == Vector(ProposalId.Eip(1283), ProposalId.Eip(1283)),
      "the record is a journal of adoptions, so a proposal adopted twice is recorded twice"
    )

  it should "leave its rule off, so membership in the record does not mean in force" in
    // The reading this refuses is *components is the set of proposals active
    // here*, which is wrong on any network that turns one off again. The record
    // says what was adopted; only the facets say what is in force.
    assert(
      !firstRules.adopting(bounds, unbounds).admission.signatureSMustBeLow,
      "a rule a later component reversed was read back as still in force"
    )

  "two rule sets" should "compare equal when the facets added last are built separately" in {
    // The machine's facet is shared by reference here on purpose -- rebuilding
    // it member by member is `EvmRulesSpec`'s claim, and repeating it would
    // make this fail for that reason instead of this one. What is genuinely
    // rebuilt is the two records this comparison is being checked for.
    val rebuilt = UpgradeRules(
      components = firstRules.components,
      evm = firstRules.evm,
      execution = ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false),
      admission = AdmissionRules(signatureSMustBeLow = false)
    )
    assert(rebuilt == firstRules, "a facet built separately made two identical rule sets compare as different")
  }
