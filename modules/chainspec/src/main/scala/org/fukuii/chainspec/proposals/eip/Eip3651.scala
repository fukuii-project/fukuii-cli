package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-3651 -- the account a block pays its fees to is reached at the reduced
  * price from the start.
  *
  * ==One sentence of specification, and it is the whole document==
  *
  * *"At the start of transaction execution, `accessed_addresses` shall be
  * initialized to also include the address returned by `COINBASE` (`0x41`)"*
  * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-3651.md`, Final).
  * There is no second clause: no figure moves, no operation is added, and the
  * word *also* is doing the work -- the four members already in that set stay in
  * it.
  *
  * ==What it does NOT reach, and one of the two is easy to get wrong==
  *
  * **The schedule.** *"in accordance with the actual cost of reading that
  * account"* is the document's abstract explaining why the address belongs in
  * the set, not a repricing: what a warm reach and a cold reach cost are
  * unchanged, and this delta touches no price.
  *
  * **The charge a transaction pays before it runs.** The address joins the set
  * that seeds the warm addresses, and that set is not what the intrinsic charge
  * counts. `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) makes the
  * distinction visible by reusing one name for both: `access_list_addresses`
  * gains the beneficiary at `forks/shanghai/fork.py:569`, while
  * `calculate_intrinsic_cost` loops `tx.access_list` --- the transaction's own
  * declaration --- at `forks/shanghai/transactions.py:391`. A build that charged
  * the seed would bill every transaction for one declared address it never
  * declared.
  *
  * ==`requires: 2929`, and the dependency is real rather than editorial==
  *
  * Below that document there is no warm set to be a member of, so this delta is
  * expressible and inert: `org.fukuii.execution.TransactionProcessor` seeds
  * nothing at all where the metering is settled, so a rule set holding this
  * true under the earlier scheme runs identically to one holding it false.
  * **The inertness is a property of the seed and not a guard**, which is why
  * nothing here checks the predecessor -- see
  * `org.fukuii.evm.EvmRules.coinbaseStartsWarm`.
  *
  * ==The reach it changes is the one a transaction cannot avoid==
  *
  * The document's motivation is direct payment to that account, which is why the
  * change is worth its own proposal rather than being a rounding of the
  * metering: *"Direct `COINBASE` payments are becoming increasingly popular
  * because they allow conditional payments"*, and the address *"should also be
  * always be loaded because it receives the block reward and the transaction
  * fees"*. Every block already touches it, so the earlier scheme was charging
  * the first-reach price for an account no node could avoid reading.
  */
object Eip3651:

  /** The block's beneficiary joins what the outermost invocation may reach at
    * the reduced price before it has reached anything.
    */
  val warmBeneficiary: Proposal = _.copy(coinbaseStartsWarm = true)

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(3651), warmBeneficiary)
