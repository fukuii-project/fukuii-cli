package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, DifficultyAdjustment, ProposalId}
import org.fukuii.evm.Proposal
import org.fukuii.execution.AdmissionRules

/** EIP-2 -- four changes under one document number, and all four are here.
  *
  * ==One proposal is not one delta, and flattening them loses the difference==
  *
  * This document moves a price, settles a behavior in the machine, settles a
  * second behavior OUTSIDE it, and replaces a consensus formula -- four changes
  * of four different kinds, reaching three facets. Written as a single delta
  * they would be indistinguishable from each other in every reading that
  * follows: which of them a later proposal collides with, which of them a
  * network could adopt alone, and which layer enforces each.
  *
  * So the deltas are named individually and the document is what bundles them.
  * [[component]] is the adoption of all four, which is the only form any network
  * in this project's scope actually took.
  *
  * ==This is the document both surveyed clients mis-filed, and the reason the
  * four are not filed together==
  *
  * `openethereum/openethereum` @ `v3.0.1` reaches its difficulty rules only
  * through an `ethash_extensions` option, and `ethcore/spec/src/spec.rs:418`
  * carries a comment about a network it therefore cannot express; the same
  * filing makes `check_low_s` in `ethcore/machine/src/machine.rs`
  * unconditionally true off that path, so **item 2 of this document becomes
  * unschedulable on a network running another mechanism**.
  * `NethermindEth/nethermind` @ `c35ce1b1ab` reproduces the consequence
  * independently, setting `IsEip2Enabled` unconditionally in its general path
  * and narrowing it only inside
  * `EthashChainSpecEngineParameters.ApplyToReleaseSpec`.
  *
  * **A member belongs under a mechanism only where it touches ONLY consensus**,
  * and this document is the case that proves the rule: item 4 does, items 1
  * through 3 do not, and filing the document by its mechanism-facing item is
  * what both clients did.
  */
object Eip2:

  /** A transaction that deploys pays a surcharge before it runs.
    *
    * A repricing in place: the field exists in the original schedule priced at
    * nothing, so this moves a number and changes no shape. **It is only
    * observable on a transaction whose recipient is absent**, which is the path
    * the harness could not execute at all until it was taught to.
    */
  val creationCharge: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionCreate = BigInt(32000)))

  /** A deployment that cannot pay to store its code fails outright.
    *
    * A behavior and neither an entry nor a price, which is the delta kind that
    * forced the rules to be a value at all. Before this the account is left
    * behind holding nothing and the gas already spent stays spent; this undoes
    * the creation and takes it.
    */
  val codeDepositMustSucceed: Proposal = _.copy(codeDepositMustSucceed = true)

  /** A signature whose `s` is above half the curve order is refused.
    *
    * The comparison is strict in the specification, so `s` exactly at half the
    * order stays valid and only what is above it is refused.
    *
    * **This one is written over the admission facet rather than the machine's**,
    * which is the difference the four deltas were separated to keep visible: a
    * transaction refused here never reaches the machine at all, so no rule the
    * machine holds could express it.
    */
  val lowSignatureS: AdmissionRules => AdmissionRules = _.copy(signatureSMustBeLow = true)

  /** The two-valued difficulty adjustment is replaced by a continuous one.
    *
    * The document's fourth item, quoted in full on
    * [[org.fukuii.chainspec.DifficultyAdjustment]] along with the rule it
    * replaces. **This delta selects the algorithm and moves no other member**:
    * the item states no change to the bound divisor and none to the exponential
    * term, and both surveyed clients that gate on this proposal --
    * `ethereumclassic/core-geth` @ `4185df450` on `GetEIP2Transition` and
    * `openethereum/openethereum` @ `v3.0.1` on `homestead_transition` -- switch
    * the multiplier alone.
    *
    * **This one is written over the consensus facet, which is the third layer
    * the document reaches** and the difference the four deltas were separated to
    * keep visible.
    */
  val difficultyAdjustment: ConsensusRules => ConsensusRules =
    _.copy(difficultyAdjustment = DifficultyAdjustment.Eip2)

  /** Adopting the document, which is adopting all four of the deltas above.
    *
    * ==Built from the general constructor, because this document spans facets==
    *
    * `Component.evm` reaches the machine and nothing else, which is what makes
    * it safe for a proposal confined there and unusable for one that is not.
    * This document settles a rule in three layers, so it names all three.
    *
    * The order is the order they compose in. It is immaterial here -- the four
    * touch disjoint fields -- and it is stated rather than left to chance
    * because two deltas touching one field compose to whichever ran last.
    */
  val component: Component =
    Component(
      ProposalId.Eip(2),
      rules =>
        rules.copy(
          evm = rules.evm.applying(creationCharge, codeDepositMustSucceed),
          admission = lowSignatureS(rules.admission),
          consensus = difficultyAdjustment(rules.consensus)
        )
    )
