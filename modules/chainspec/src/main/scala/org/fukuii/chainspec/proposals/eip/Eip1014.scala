package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-1014 -- `CREATE2`.
  *
  * ==One entry, and its price is COMPUTED rather than settled==
  *
  * *"a new opcode at `0xf5`"* whose gas is *"the same as `CREATE`"* plus *"an
  * extra `hashcost` of `GSHA3WORD * ceil(len(init_code) / 32)`"*, *"deducted at
  * the same time as memory-expansion gas and `CreateGas`"* (`ethereum/EIPs` @
  * `dbfa6bee`, `EIPS/eip-1014.md`, Final). Two of those three terms depend on
  * an operand, so no fixed entry can state the charge and `Cost.Computed` is
  * the only honest one -- the same reason `CREATE` itself carries it.
  *
  * `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/constantinople/vm/instructions/system.py:193-197`, charges
  * `OPCODE_CREATE_BASE + OPCODE_KECCAK256_PER_WORD * call_data_words +
  * extend_memory.cost`, which is that sentence as arithmetic.
  *
  * **So this document introduces no figure either.** It reuses `createBase`
  * and the per-word rate `KECCAK256` already charges, and
  * `org.fukuii.evm.Interpreter` is where the sum lives.
  *
  * ==What the operation is FOR, which the price does not show==
  *
  * `CREATE` derives an address from the creator's transaction count, so every
  * creation consumes the next one and a creator can never return to an address
  * it has passed. `CREATE2` derives it from a salt and the initialization code
  * instead: the address is computable before anything is created, and is
  * reachable again after a self-destruct. `org.fukuii.evm.ContractAddress`
  * carries the derivation and the reason its preimage begins `0xff`.
  *
  * **The creator's count is still read and still incremented.** A salted
  * creation consumes the creator's next ordinary address exactly as an unsalted
  * one does; what the salt changes is only what the new address is derived
  * from. That is easy to get wrong in the permissive direction, and it is the
  * specification's behavior rather than a consequence of sharing an
  * implementation with `CREATE`.
  */
object Eip1014:

  /** The operation joins the table, priced from its operands. */
  val create2: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Create2, Cost.Computed)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(1014), create2)
