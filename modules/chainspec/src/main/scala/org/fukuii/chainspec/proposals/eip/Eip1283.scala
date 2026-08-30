package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Proposal, StorageMetering}

/** EIP-1283 -- net gas metering for `SSTORE`.
  *
  * ==One switch here, and the whole scheme in the machine==
  *
  * The document *"Replace `SSTORE` opcode gas cost calculation (including
  * refunds) with the following logic"* and then states nine clauses over three
  * values -- what the slot held at the start of the transaction, what it holds
  * now, and what is being written (`ethereum/EIPs` @ `dbfa6bee`,
  * `EIPS/eip-1283.md`, Final). `SSTORE` is priced from its operands and carries
  * `Cost.Computed`, so there is no table entry to move; what a network settles
  * is WHICH scheme is in force, and `org.fukuii.evm.StorageMetering` is that.
  *
  * The seven figures are the schedule's, as every price is. The nine clauses
  * are `org.fukuii.evm.Interpreter`'s.
  *
  * ==This proposal was adopted and withdrawn at ONE height on the network that
  * specified it==
  *
  * Ethereum mainnet activates Constantinople and Petersburg at the same block,
  * so **the scheme was never in force there** -- see `Mainnet`'s two entries at
  * that height, and [[Eip1716]] for the withdrawal.
  *
  * **It is built anyway, and not as an academic exercise.** It was in force on
  * Ropsten for 709,394 blocks, on Kovan for 1,055,201 and on Rinkeby for
  * 660,571 -- `EIPS/eip-1716.md`'s own activation table, matched to the digit
  * by `ethereum/go-ethereum-pow` @ `v1.10.26`, `params/config.go:116-117` and
  * `:189-190`. Gnosis turned it on, off, and on again. A client that cannot
  * express the scheme cannot follow any of those chains, and cannot state what
  * Ethereum's own Constantinople was specified to be.
  *
  * ==What it needs that nothing else in this build needed==
  *
  * A read of the slot's value at the start of the transaction --
  * `org.fukuii.evm.WorldState.committedStorageAt`, added for this -- and a
  * refund counter that can go DOWN. The document is explicit that a
  * frame-level counter must be signed and that a child's may go below zero,
  * which is this codebase's shape; the machine's own scaladoc carries that
  * quotation where the decrement is written.
  */
object Eip1283:

  /** Storage is priced against the transaction's starting value as well as the
    * current one.
    */
  val netMetering: Proposal = _.copy(storageMetering = StorageMetering.Net)

  /** Adopting the document, which is adopting its one switch. */
  val component: Component = Component.evm(ProposalId.Eip(1283), netMetering)
