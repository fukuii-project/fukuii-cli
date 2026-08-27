package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-211 -- `RETURNDATASIZE` and `RETURNDATACOPY`, and the buffer behind
  * them.
  *
  * ==Two entries here, and the rest of the document is the machine's==
  *
  * *"`RETURNDATASIZE`: `0x3d`"* and *"`RETURNDATACOPY`: `0x3e`"*
  * (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-211.md`, Final). The document also
  * amends *"the semantics of any opcode that creates a new call frame"*, which
  * is a change to what those operations do rather than to which of them exist;
  * `org.fukuii.evm.Frame.returnData` carries it, and it is live from the first
  * table that has either entry.
  *
  * ==Two prices, and neither is a member of its own==
  *
  * *"Gas costs: 2 (same as `CALLDATASIZE`)"* for the first, so it takes the
  * tier `CALLDATASIZE` takes, and the specification agrees by declaring
  * `OPCODE_RETURNDATASIZE: Final[Uint] = BASE` rather than a figure
  * (`ethereum/execution-specs` @ `20f7f6271a`,
  * `src/ethereum/forks/byzantium/vm/gas.py:128`).
  *
  * *"Gas costs: `3 + 3 * ceil(amount / 32)` (same as `CALLDATACOPY`)"* for the
  * second, which is the copying family's settled part and its per-word term
  * unchanged. **The specification declares that per-word term twice rather than
  * aliasing it**, so the two could have diverged and did not: across the 24
  * fork directories at that ref, 19 declare both `OPCODE_COPY_PER_WORD` and
  * `OPCODE_RETURNDATACOPY_PER_WORD` and all 19 give them the same value, while
  * the five declaring only the first are the forks predating this document.
  * Two production clients collapse them outright --
  * `ethereum/go-ethereum-pow` @ `v1.10.26` builds
  * `gasReturnDataCopy = memoryCopierGas(2)` from the same factory and the same
  * `params.CopyGas` as `gasCallDataCopy` (`core/vm/gas_table.go`), and
  * `besu-eth/besu` @ `fdf1247c6d`'s `ReturnDataCopyOperation` calls the same
  * `dataCopyOperationGasCost` its other copying operations call.
  *
  * Reversing trigger: the first fork or network pricing `RETURNDATACOPY`'s
  * per-word term apart from the other copying operations. At that point
  * `org.fukuii.evm.GasSchedule` gains a member, which is an addition to that
  * record rather than a change to anything reading it.
  */
object Eip211:

  /** The size of the buffer, at the tier `CALLDATASIZE` is priced from. */
  val returnDataSize: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.ReturnDataSize, Cost.Fixed(rules.schedule.base))))

  /** Copying out of the buffer, priced from its operands like every other
    * copying operation.
    */
  val returnDataCopy: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.ReturnDataCopy, Cost.Computed)))

  /** Adopting the document, which is adopting both deltas.
    *
    * The order is immaterial: the two name different bytes, so neither can
    * replace the other's entry.
    */
  val component: Component = Component.evm(ProposalId.Eip(211), returnDataSize, returnDataCopy)
