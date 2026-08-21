package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-2 -- three changes under one document number.
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
  * [[component]] is the adoption of the whole, which is the only form any
  * network in this project's scope actually took.
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
    * order stays valid and only what is above it is refused. Settled outside the
    * machine, which is why it is a rule here and a check there.
    */
  val lowSignatureS: Proposal = _.copy(signatureSMustBeLow = true)

  /** Adopting the document, which is adopting all three of its deltas.
    *
    * The order is the order they compose in. It is immaterial here -- the three
    * touch disjoint fields -- and it is stated rather than left to chance
    * because two deltas touching one field compose to whichever ran last.
    */
  val component: Component =
    Component.evm(ProposalId.Eip(2), creationCharge, codeDepositMustSucceed, lowSignatureS)
