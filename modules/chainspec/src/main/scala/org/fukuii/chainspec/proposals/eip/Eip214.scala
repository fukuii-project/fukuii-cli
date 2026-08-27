package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-214 -- `STATICCALL`, and the flag it sets on what it starts.
  *
  * ==One entry here, and the flag is the machine's==
  *
  * *"Opcode: `0xfa`"*, and the operation *"functions equivalently to a `CALL`,
  * except it takes only 6 arguments (the "value" argument is not included and
  * taken to be zero), and calls the child with the `STATIC` flag set to `true`
  * for the execution of the child"* (`ethereum/EIPs` @ `9e393a79`,
  * `EIPS/eip-214.md`, Final).
  *
  * The rest of the document amends what NINE OTHER OPERATIONS do rather than
  * which of them exist -- *"any attempts to make state-changing operations
  * inside an execution instance with `STATIC` set to `true` will instead throw
  * an exception"*, over `CREATE`, the five emitting operations, `SSTORE`,
  * `SELFDESTRUCT` and a call sending value. None of those is a table entry, so
  * none of them is expressible here: `org.fukuii.evm.Message.isStatic` carries
  * the flag and the machine carries the refusals, and both are live from the
  * first table that has this entry.
  *
  * **`CREATE2` is on the document's list and is not on this table**, having
  * arrived with a later proposal. Its refusal comes with it rather than with
  * this one, which is the ordinary consequence of a rule stated over a set the
  * document does not fix.
  *
  * ==No price of its own==
  *
  * The document names no gas figure at all. The operation is charged the call
  * family's settled part, whatever memory it reaches, and whatever it forwards,
  * which is a computation and not a figure a table can hold -- so the entry is
  * priced from its operands like the rest of that family. The specification
  * agrees structurally, passing an `extra_gas` of `OPCODE_CALL_BASE` alone where
  * its `call` builds one from three terms (`ethereum/execution-specs` @
  * `20f7f6271a`, `src/ethereum/forks/byzantium/vm/instructions/system.py`).
  */
object Eip214:

  /** The operation, priced from its operands like every other member of the
    * call family.
    */
  val staticCall: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.StaticCall, Cost.Computed)))

  /** Adopting the document, which is adopting the one entry it puts in a table.
    */
  val component: Component = Component.evm(ProposalId.Eip(214), staticCall)
