package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.fukuii.evm.{Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1153 changes, and what it must leave alone.
  *
  * ==The figure is asserted against the schedule, not against 100==
  *
  * The document names a rule and a number in one sentence -- the charge is
  * *"the same as a hot `SLOAD`"*, *"currently 100 gas"* -- and the two are not
  * the same claim. A spec asserting the number would pass for a build that
  * wrote 100 into both entries and stopped tracking the field, which is the
  * defect: a network holding a different warm figure would then price these two
  * operations at Ethereum mainnet's.
  */
class Eip1153Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.shanghai

  private val adopted: UpgradeRules = base.adopting(Eip1153.component)

  /** The same component adopted over a rule set that carries no warm-and-cold
    * scheme, so the reference the document's charge is defined by has nothing to
    * resolve to.
    */
  private val withoutAWarmScheme: UpgradeRules = ethereum.Upgrades.byzantium.adopting(Eip1153.component)

  "adopting EIP-1153" should "put an operation at 0x5c and one at 0x5d" in
    assert(
      adopted.evm.table.contains(Opcode.TLoad) && adopted.evm.table.contains(Opcode.TStore),
      "the document introduces both instructions at those bytes"
    )

  it should "have had neither before it was adopted" in
    assert(
      !base.evm.table.contains(Opcode.TLoad) && !base.evm.table.contains(Opcode.TStore),
      "the fork below already carried them, leaving the change untested"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(1153)),
      "the journal states which document produced these rules"
    )

  it should "price both at the warm-access figure the document refers to" in
    assert(
      adopted.evm.table.operationAt(0x5c).map(_.cost) == Some(Cost.Fixed(adopted.evm.schedule.warmAccess)) &&
        adopted.evm.table.operationAt(0x5d).map(_.cost) == Some(Cost.Fixed(adopted.evm.schedule.warmAccess)),
      "both entries are built from the field, so a network holding a different warm figure prices them at its own"
    )

  it should "price them apart from the tier the operations they mirror are settled at" in
    // The failure this catches is the pair being priced from a tier rather than
    // from the warm figure. Both are read from the schedule rather than written
    // out, so this compares the rule against the rule and not against literals.
    assert(
      adopted.evm.schedule.warmAccess != adopted.evm.schedule.veryLow &&
        adopted.evm.schedule.warmAccess != adopted.evm.schedule.base,
      "the warm-access figure is neither of the two tiers a settled operation is ordinarily priced at"
    )

  "the figure both entries carry" should "come from the rule set they are adopted over" in
    // The document defines the charge by reference to a warm-and-cold scheme's
    // warm figure. A rule set that adopted no such scheme holds nothing for it,
    // and these entries carry that nothing -- which is the reference working
    // rather than failing, and is the whole of what makes adopting this document
    // over such a rule set a decision rather than a default. Both halves are
    // read from their own schedule, so neither is a literal to go stale.
    assert(
      adopted.evm.schedule.warmAccess > 0 &&
        withoutAWarmScheme.evm.schedule.warmAccess == 0 &&
        withoutAWarmScheme.evm.table.operationAt(0x5c).map(_.cost) ==
        Some(Cost.Fixed(withoutAWarmScheme.evm.schedule.warmAccess)),
      "a rule set below the scheme this charge refers to prices both operations at what it holds, which is nothing"
    )

  it should "add exactly two operations" in
    assert(adopted.evm.table.size == base.evm.table.size + 2, "two entries join the table and nothing leaves it")

  it should "move no price at all" in
    // The field both entries are built from is already what the rules hold, so
    // this document adds two operations without repricing anything.
    assert(adopted.evm.schedule == base.evm.schedule, "no figure moves, and the two schedules are the same value")

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.admission, adopted.consensus, adopted.header) ==
        (base.execution, base.admission, base.consensus, base.header),
      "two entries in one facet is the whole delta"
    )

  it should "leave how reaching state is metered alone" in
    // The two operations these are priced against are metered warm-or-cold, and
    // these are not. A component that had moved that member to fix the price
    // would have repriced eleven other operations to place two.
    assert(
      adopted.evm.stateAccessMetering == base.evm.stateAccessMetering,
      "the keyspace has no committed value, so there is no cold state for a scheme to charge for"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The keyspace lives on the journal every invocation already runs against,
    // so there is no value a proof-of-work network would lack. What such a
    // network would have to hold is a warm-access figure, and every schedule
    // does.
    val powBase = ethereumclassic.Upgrades.spiral
    val powAdopted = powBase.adopting(Eip1153.component)
    assert(
      powAdopted.evm.table.contains(Opcode.TLoad) && !powBase.evm.table.contains(Opcode.TLoad),
      "the entries land on a proof-of-work rule set exactly as they land on this one"
    )
  }
