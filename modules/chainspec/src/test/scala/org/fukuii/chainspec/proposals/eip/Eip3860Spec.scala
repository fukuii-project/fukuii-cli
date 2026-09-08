package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3860 changes, and what its `requires: 170` costs where it
  * is unmet.
  *
  * ==The bound is DERIVED, so the case that matters is the derivation and not
  * the figure==
  *
  * *"`MAX_INITCODE_SIZE = 2 * MAX_CODE_SIZE`"*, where the doubled bound is
  * EIP-170's. A spec asserting 49,152 would pass for a build that wrote the
  * figure out, and that build parts from the field at the first fork that moves
  * the bound it doubles -- which `ethereum/go-ethereum` @ `e9e35a42f` already
  * has, declaring a second pair for a later fork two lines below the first
  * (`params/protocol_params.go:161,163`). So the figure and the relation are
  * asserted separately.
  *
  * ==What the four rules DO is asserted where each of them lives==
  *
  * This spec covers the delta over a rule set. The abort and the extra charge
  * inside the machine are `org.fukuii.evm.InitcodeMeteringSpec`'s; the refusal
  * and the intrinsic term outside it are
  * `org.fukuii.execution.InitcodeAdmissionSpec`'s and
  * `org.fukuii.execution.IntrinsicGasSpec`'s. **A rule set cannot show any of
  * the four happening**, only that the two values they read were set.
  */
class Eip3860Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.paris

  private val adopted: UpgradeRules = base.adopting(Eip3860.component)

  /** A rule set that has adopted no bound on deployed code, which is every
    * height below EIP-170 and is what this document's own frontmatter requires.
    */
  private val unbounded: UpgradeRules = ethereum.Upgrades.frontier

  "adopting EIP-3860" should "bound the code a creation may be initialized from" in
    assert(adopted.evm.maxInitcodeSize.isDefined, "the document introduces a bound where there was none")

  it should "not have been bounded before it was adopted" in
    assert(
      base.evm.maxInitcodeSize.isEmpty,
      "the fork below already bounded it, leaving the change untested"
    )

  it should "set the bound at twice the bound on deployed code" in
    // The relation, which is what the document defines. Read from the rules
    // rather than written out, so a build that froze the figure fails here.
    assert(
      adopted.evm.maxInitcodeSize == base.evm.maxCodeSize.map(_ * 2),
      "the bound is a doubling of EIP-170's, and the thing doubled is read from the rules"
    )

  it should "reach 49,152 on this network, which is what the doubling comes to here" in
    // The figure as well as the relation: the case above would be satisfied by a
    // build that doubled the wrong field, since two absent options also compare
    // equal.
    assert(
      adopted.evm.maxInitcodeSize.contains(49152) && base.evm.maxCodeSize.contains(24576),
      "24,576 doubled, which is the number the document states for a network at EIP-170"
    )

  it should "charge two gas for each whole word of that code" in
    assert(adopted.evm.schedule.initcodePerWord == BigInt(2), "INITCODE_WORD_COST is 2")

  it should "have charged nothing for it before it was adopted" in
    assert(
      base.evm.schedule.initcodePerWord == BigInt(0),
      "the rate is held at zero below the document, where it prices nothing"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(3860)),
      "the journal states which document produced these rules"
    )

  "adopting it over a rule set that bounds no deployed code" should "bound no initcode either" in
    // `requires: 170` read from the other side, and the state is recorded rather
    // than guarded: doubling nothing is nothing, so rules 1 and 3 refuse nothing
    // on such a network. The alternative is a literal, which would state a bound
    // derived from a code size the network does not have.
    assert(
      unbounded.adopting(Eip3860.component).evm.maxInitcodeSize.isEmpty,
      "the derivation cannot disagree with its own premise, and an unmet requirement is visible here"
    )

  it should "still charge the rate" in
    // The other half of the same state, stated so it is not mistaken for the
    // document being inert: the metering is not derived from anything and
    // applies whatever the bound is.
    assert(
      unbounded.adopting(Eip3860.component).evm.schedule.initcodePerWord == BigInt(2),
      "the rate is the document's own figure and does not depend on EIP-170"
    )

  "the delta" should "leave the machine's operations exactly as they were" in
    assert(adopted.evm.table == base.evm.table, "no operation is added, removed or repriced")

  it should "move no price but the one it names" in
    assert(
      adopted.evm.schedule == base.evm.schedule.copy(initcodePerWord = BigInt(2)),
      "one field of the schedule moves and every other is the same value"
    )

  it should "leave the bound on DEPLOYED code where EIP-170 put it" in
    // The two bounds are different numbers over different bytes at different
    // moments. "We extend EIP-170" is beside, not instead of -- and a delta that
    // overwrote the deployed bound with the doubled one would satisfy every case
    // above.
    assert(
      adopted.evm.maxCodeSize == base.evm.maxCodeSize,
      "the code a creation leaves behind is bounded by the earlier document, unchanged"
    )

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.admission, adopted.consensus, adopted.header) ==
        (base.execution, base.admission, base.consensus, base.header),
      "two members of one facet are the whole delta"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The bound derives from a bound that network already has, and the rate is
    // the document's own figure. Neither needs anything a beacon chain
    // supplies, so this document is adoptable independently of the rest of the
    // upgrade that shipped it.
    val powBase = ethereumclassic.Upgrades.mystique
    val powAdopted = powBase.adopting(Eip3860.component)
    assert(
      powAdopted.evm.maxInitcodeSize == powBase.evm.maxCodeSize.map(_ * 2) &&
        powAdopted.evm.schedule.initcodePerWord == BigInt(2),
      "both members land on a proof-of-work rule set exactly as they land on this one"
    )
  }
