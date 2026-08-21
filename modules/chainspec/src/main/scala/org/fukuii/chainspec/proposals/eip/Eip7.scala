package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-7 -- `DELEGATECALL`.
  *
  * One delta, so the component and the delta look alike here. They are still
  * separate: the delta is what changes, the component is the adoption of a
  * numbered document, and [[Eip2]] is the file where that difference is
  * visible.
  */
object Eip7:

  /** The operation joins the table, and nothing else changes.
    *
    * What the operation *does* is the machine's; that it exists at all is this.
    */
  val delegateCall: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.DelegateCall, Cost.Computed)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(7), delegateCall)
