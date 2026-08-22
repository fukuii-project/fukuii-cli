package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal
import org.fukuii.execution.AdmissionRules

/** EIP-2 -- four changes under one document number, of which three are here.
  *
  * ==One proposal is not one delta, and flattening them loses the difference==
  *
  * This document moves a price, settles a behavior in the machine, and settles
  * a second behavior OUTSIDE it -- three changes of three different kinds.
  * Written as a single delta they would be indistinguishable from each other in
  * every reading that follows: which of them a later proposal collides with,
  * which of them a network could adopt alone, and which layer enforces each.
  *
  * So the deltas are named individually and the document is what bundles them.
  * [[component]] is the adoption of the whole of what is modeled, which is the
  * only form any network in this project's scope actually took.
  *
  * ==The fourth item is the difficulty formula, and it is not modeled here==
  *
  * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-2
  * *Homestead Hard-fork Changes* (Final), states four numbered items under
  * *Specification*, and the fourth replaces the difficulty adjustment
  * algorithm. It belongs to the consensus facet, which
  * [[org.fukuii.chainspec.UpgradeRules]] names as arriving with the consensus
  * engine and which does not exist yet -- so the rule sets this component
  * composes are Homestead's first three items and not Homestead.
  *
  * **Recorded because nothing else records it.** The three deltas below read as
  * the whole document, so a reader has no way to tell a proposal fully modeled
  * from one modeled as far as its layers exist, and whoever builds the
  * consensus facet has nothing pointing at the rule this proposal owes it.
  * `ethereum.Mainnet`'s Homestead entry states the same kind of omission for
  * EIP-606's own.
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
    * which is the difference the three deltas were separated to keep visible: a
    * transaction refused here never reaches the machine at all, so no rule the
    * machine holds could express it.
    */
  val lowSignatureS: AdmissionRules => AdmissionRules = _.copy(signatureSMustBeLow = true)

  /** Adopting the document, which is adopting all three of the deltas above.
    *
    * ==Built from the general constructor, because this document spans facets==
    *
    * `Component.evm` reaches the machine and nothing else, which is what makes
    * it safe for a proposal confined there and unusable for one that is not.
    * This document settles a rule in two layers, so it names both.
    *
    * The order is the order they compose in. It is immaterial here -- the three
    * touch disjoint fields -- and it is stated rather than left to chance
    * because two deltas touching one field compose to whichever ran last.
    */
  val component: Component =
    Component(
      ProposalId.Eip(2),
      rules =>
        rules.copy(
          evm = rules.evm.applying(creationCharge, codeDepositMustSucceed),
          admission = lowSignatureS(rules.admission)
        )
    )
