package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Proposal, SelfDestructScope}

/** EIP-6780 -- a destruction that removes only what its own transaction made.
  *
  * ==One member moves, and it is not the table and not the schedule==
  *
  * *"`SELFDESTRUCT` will recover all funds to the target but not delete the
  * account, except when called in the same transaction as creation"*
  * (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-09), `EIPS/eip-6780.md`, Final). The
  * operation stays at its byte, its stack is unchanged, and the document says
  * in terms that it moves no price: *"the rules of EIP-2929 regarding
  * `SELFDESTRUCT` remain unchanged"*, and *"no refund is given since
  * EIP-3529"*, which is a fork below rather than a change here. So the delta is
  * `org.fukuii.evm.EvmRules.selfDestructScope` and nothing else.
  *
  * ==It changes TWO behaviors and the second is easy to miss==
  *
  * The removal is the one the title names. The other is the balance: under the
  * rule below, an account that predates the transaction and names ITSELF as its
  * beneficiary keeps what it held, where before it lost it. The document states
  * both halves and states them apart --
  *
  *   - not created here: *"if the target is the same as the contract calling
  *     `SELFDESTRUCT` there is no net change in balances. Unlike the prior
  *     specification, Ether will not be burnt in this case"*;
  *   - created here: *"the account balance of the contact calling
  *     `SELFDESTRUCT` is set to `0`"*, and *"if the target is the same as the
  *     contract calling `SELFDESTRUCT` that Ether will be burnt"*.
  *
  * -- and gathers them in one sentence under backwards compatibility: *"If the
  * contract existed prior to the transaction the ether will not be burned. If
  * the contract was newly created in the transaction the ether will be burned,
  * as before"* (same document). **A build that gated only the removal would
  * agree with this document about every account and disagree about the value of
  * one**, which is why the two acts sit under one condition in the machine.
  *
  * ==What it does NOT reach==
  *
  * No opcode joins or leaves the table, no figure moves, and nothing about
  * admission, execution or the header changes. It is the narrowest kind of
  * delta this record admits: one member, one value.
  *
  * ==The record it turns on is the machine's, and it has a lifetime nothing
  * else here has==
  *
  * *"A contract is considered created at the beginning of a create transaction
  * or when a CREATE series operation begins execution"* (same document), and
  * that fact has to outlive an invocation that fails -- which is the one
  * property `org.fukuii.evm.JournaledWorldState` gives no other member it
  * holds. A component cannot state a lifetime; where that one is kept, and what
  * the two production clients do differently there, is recorded on the member
  * itself.
  */
object Eip6780:

  /** A destruction reaches only an account this transaction created. */
  val destroyOnlyWhatThisTransactionCreated: Proposal =
    rules => rules.copy(selfDestructScope = SelfDestructScope.AccountsCreatedInTransaction)

  /** Adopting the document, which is adopting its one delta. */
  val component: Component =
    Component.evm(ProposalId.Eip(6780), destroyOnlyWhatThisTransactionCreated)
