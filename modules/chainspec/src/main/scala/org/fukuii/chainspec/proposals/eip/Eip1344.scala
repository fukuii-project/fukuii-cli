package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-1344 -- `CHAINID`.
  *
  * ==One entry, at a tier rather than at a price of its own==
  *
  * *"Adds a new opcode `CHAINID` at 0x46, which uses 0 stack arguments. It
  * pushes the current chain ID onto the stack. Chain ID is a 256-bit value. The
  * operation costs `G_base` to execute."* (`ethereum/EIPs` @ `dbfa6bee`,
  * `EIPS/eip-1344.md`, Final). `G_base` is the base tier, which
  * `org.fukuii.evm.GasSchedule.base` holds and both networks here state at 2.
  *
  * **The corroborating source declares this operation's price AS the tier
  * rather than as a number**, and the entry below names the tier for the same
  * reason: `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/istanbul/vm/gas.py:135`, `OPCODE_CHAINID: Final[Uint] = BASE`, with
  * `BASE: Final[Uint] = Uint(2)` at `:33`. A proposal that moved the base tier
  * would move this operation with it, which is what the specification
  * describes; a literal 2 here would silently opt the operation out of its own
  * tier.
  *
  * ==What it does NOT reach==
  *
  * The schedule. No figure moves -- the tier this entry is built from is already
  * what the rules hold, so this document adds an operation without repricing
  * anything.
  *
  * ==Where the value comes from is the machine's, and it is configuration
  * rather than state==
  *
  * *"The value of the current chain ID is obtained from the chain ID
  * configuration, which should match the EIP-155 unique identifier a client will
  * accept from incoming transactions."* So the operation reads nothing from the
  * world and nothing from the block: `org.fukuii.evm.Environment.chainId` is
  * what it pushes, and the document is explicit that a transaction carrying no
  * such identifier does not change the answer.
  */
object Eip1344:

  /** The operation joins the table at the tier the document names. */
  val chainId: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.ChainId, Cost.Fixed(rules.schedule.base))))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(1344), chainId)
