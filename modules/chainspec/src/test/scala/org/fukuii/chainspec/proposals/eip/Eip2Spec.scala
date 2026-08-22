package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.Bytes
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.execution.IntrinsicGas
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2 actually changes, asserted through the component.
  *
  * ==Through [[Eip2.component]], because the wiring is what is untested==
  *
  * Each delta this document carries is reachable on its own, and a spec calling
  * the three of them directly passes with the component wired to none of them.
  * What a network adopts is the component, so the component is what is adopted
  * here: a delta left unwired is then a failing case rather than a rule quietly
  * absent from every rule set from this fork onward.
  *
  * The recorded proposal list cannot stand in for this. `UpgradeRules.adopting`
  * rebuilds that list from the identifiers it was passed, so it reads the same
  * whatever the delta did or did not do.
  *
  * ==Here rather than with the layers, because these are the document's claims==
  *
  * Every figure is read from the proposal's own text. The machine and the
  * admission facet each know how to hold a rule and neither knows which rule is
  * right; asserting both in one place would leave a failure unable to say which
  * of the two was wrong.
  */
class Eip2Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.frontier

  private val adopted: UpgradeRules = base.adopting(Eip2.component)

  /** What a transaction stating no recipient and carrying no input is charged
    * before it runs, which is the figure the document states directly.
    */
  private def creationCharge(rules: UpgradeRules): BigInt =
    IntrinsicGas.of(rules.evm.schedule, Bytes.Empty, deploys = true)

  "adopting EIP-2" should "refuse a signature whose s is above half the curve order" in
    // The document's one rule outside the machine, and the delta whose wiring
    // nothing else observes: a rule set that reached admission without it
    // accepts the malleated duplicate of every transaction from this fork on.
    assert(
      adopted.admission.signatureSMustBeLow,
      "the bound on a signature's s did not reach the admission facet"
    )

  it should "charge a transaction that creates a contract 53,000 before its data" in
    // Stated as the whole charge rather than as the surcharge, because 53,000 is
    // the figure the document states and the surcharge is this project's own
    // decomposition of it into a base and a delta over that base.
    assert(
      creationCharge(adopted) == BigInt(53000),
      "a transaction stating no recipient is not charged what the proposal says it is"
    )

  it should "fail a deployment that cannot pay to store its code" in
    assert(
      adopted.evm.codeDepositMustSucceed,
      "the rule that undoes a creation which cannot pay its deposit did not reach the machine"
    )

  it should "have held none of the three before it was adopted, or the cases above test nothing" in
    // The control. Two of the three are booleans that are false at genesis, so a
    // case asserting the adopted value alone would pass against a base that
    // already held it. The third is the document's own before figure.
    assert(
      !base.admission.signatureSMustBeLow && !base.evm.codeDepositMustSucceed &&
        creationCharge(base) == BigInt(21000),
      "the genesis rules already held what this document is supposed to settle"
    )
