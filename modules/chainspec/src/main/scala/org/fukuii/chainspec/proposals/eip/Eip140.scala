package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-140 -- `REVERT`, the first failure a chain can reach that keeps its gas.
  *
  * ==One entry in the table, and a machine that already knows what to do with
  * it==
  *
  * The document introduces one operation: *"the `REVERT` instruction is
  * introduced at `0xfd`. It expects two stack items, the top item is the
  * `memory_offset` followed by `memory_length`"* (`ethereum/EIPs` @
  * `9e393a79`, `EIPS/eip-140.md`, Final). What that operation *does* --
  * `org.fukuii.evm.Outcome.Reverted`, the rollback, the gas kept and the
  * payload handed back -- is the machine's, and is reachable from a table this
  * delta has not been applied to only by being unreachable at all.
  *
  * ==The price is worked out and not settled, which is the document's own
  * wording==
  *
  * *"The cost of the `REVERT` instruction equals to that of the `RETURN`
  * instruction, i.e. the rollback itself does not consume all gas, the contract
  * only has to pay for memory."* Memory is an operand-dependent charge, so the
  * entry names no figure -- `ethereum/execution-specs` @ `20f7f6271a` declares
  * no constant for it either, its `revert` charging `extend_memory.cost` alone
  * (`src/ethereum/forks/byzantium/vm/instructions/system.py:609`), and
  * `org.fukuii.evm.Opcode.Return` is priced the same way for the same reason.
  *
  * ==It is adopted with EIP-211 rather than before it==
  *
  * The document's own specification asserts something about the return-data
  * buffer -- *"the error message will be available to the caller in the
  * returndata buffer"* -- and that buffer is EIP-211's. A network taking this
  * without that one would run an operation whose payload nothing could read.
  */
object Eip140:

  /** The operation joins the table, and nothing else changes. */
  val revert: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Revert, Cost.Computed)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(140), revert)
