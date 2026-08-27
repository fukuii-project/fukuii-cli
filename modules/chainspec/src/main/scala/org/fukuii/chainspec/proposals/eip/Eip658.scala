package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.execution.ExecutionRules

/** EIP-658 -- a receipt states whether its transaction succeeded, in the field
  * that used to hold a root.
  *
  * ==One field, and it is a replacement rather than an addition==
  *
  * *"For blocks where `block.number >= BYZANTIUM_FORK_BLKNUM`, the intermediate
  * state root is replaced by a status code, 0 indicating failure (due to any
  * operation that can cause the transaction or top-level call to revert) and 1
  * indicating success"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-658.md`,
  * Final).
  *
  * **Nothing else in a receipt moves, and that was checked rather than
  * assumed.** `ethereum/execution-specs` @ `20f7f6271a` declares four fields on
  * `Receipt` at both sides of the boundary and the last three are identical
  * text: `forks/spurious_dragon/blocks.py` opens with `post_state: Root` and
  * `forks/byzantium/blocks.py` with `succeeded: bool`, and both then declare
  * `cumulative_gas_used`, `bloom` and `logs`. `org.fukuii.types.Receipt` is one
  * shape for that reason and `org.fukuii.types.PostStateOrStatus` is where the
  * two first fields live together.
  *
  * ==The document's dependency is on the operation, not on this delta==
  *
  * Its header is `requires: 140`, and its Motivation gives the reason: with
  * `REVERT` *"it is no longer possible for users to assume that a transaction
  * failed iff it consumed all gas"*, so a caller has no way to tell a failure
  * that kept its gas from a success. A status is that way.
  *
  * **So a network adopting this without EIP-140 would be answerable rather than
  * ill-formed** -- an exceptional halt is still a failure and still gets a zero
  * -- and the composition that adopts this one carries EIP-140 already.
  * `org.fukuii.execution.Settlement.succeeded` is what the field is built from,
  * and `org.fukuii.execution.TransactionProcessor` settles a revert as a failure
  * there, which is what makes the two documents agree at the receipt.
  *
  * ==Written over the settlement facet alone, and there is no narrower
  * constructor==
  *
  * `Component.evm` reaches the machine and nothing else, which is the wrong
  * half: this document reaches no operation, no price and no native. What it
  * settles is one rule the settlement path reads, so the general constructor is
  * used -- as `Eip161` and `Eip2` are, for the opposite reason, because they
  * span more than one facet.
  */
object Eip658:

  /** A receipt's first field states that the transaction succeeded, in place of
    * the root the state reached after it.
    *
    * A flag rather than a value, because the document replaces one field with
    * another and neither side of the replacement is parameterized.
    * `org.fukuii.execution.ExecutionRules.receiptCarriesStatus` carries the
    * argument for where the rule lives, and
    * `org.fukuii.execution.BlockProcessor` is the one site that reads it.
    */
  val statusInPlaceOfTheRoot: ExecutionRules => ExecutionRules = _.copy(receiptCarriesStatus = true)

  /** Adopting the document, which is adopting its one delta. */
  val component: Component =
    Component(ProposalId.Eip(658), rules => rules.copy(execution = statusInPlaceOfTheRoot(rules.execution)))
