package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-5656 -- an operation that copies one region of memory to another.
  *
  * ==One entry, and the table holds no number for it==
  *
  * *"The instruction `MCOPY` is introduced at `0x5E`"*, over a stack of `dst`,
  * `src` and `length` and a charge the document writes as `g_verylow` plus
  * `3 * words_copied + memory_expansion_cost` (`ethereum/EIPs` @ `d2a64c2d4`
  * (2026-09-09), `EIPS/eip-5656.md`, Final). Two of those three terms depend on
  * the operands, so the entry is [[org.fukuii.evm.Cost.Computed]] and the whole
  * price is worked out where the operation runs -- which is what that case
  * exists for, and is the same shape the three copying operations already in
  * the table carry.
  *
  * ==So this document reprices nothing, and the figures it spends are already
  * there==
  *
  * The two the machine reads are `org.fukuii.evm.GasSchedule.veryLow` and
  * `org.fukuii.evm.GasSchedule.copyPerWord`, and every network has held both
  * since its genesis: the document places the operation in the yellow paper's
  * `W_copy` group and says the charge *"mirrors that of other `Wcopy`
  * instructions"*, so it joins a family rather than founding one. **A schedule
  * field of its own would have been the wrong shape** -- it would let a network
  * move this operation's per-word figure without moving `CALLDATACOPY`'s, which
  * no document sanctions and which the two corroborating sources make
  * unwritable: `ethereum/go-ethereum` @ `02872e9ef` builds it as
  * `memoryCopierGas(2)`, the identical value `gasCallDataCopy` and `gasCodeCopy`
  * are built from (`core/vm/gas_table.go:91-93`), and `besu-eth/besu` @
  * `b330564a9` routes it through the same `dataCopyOperationGasCost` those
  * operations use (`MCopyOperation.java`).
  *
  * ==What it does NOT reach==
  *
  * Anything outside the machine. No account is read, no state is written, and
  * nothing about a transaction or a block is consulted -- the operation is a
  * function of one frame's own memory, which makes this the narrowest kind of
  * component there is.
  *
  * ==The overlap rule is the machine's to keep and is not expressible here==
  *
  * *"Copying takes place as if an intermediate buffer was used, allowing the
  * destination and source to overlap"* (same document). A component adds an
  * entry to a table; it cannot state how the operation behaves. Where that rule
  * is kept, and why it is already kept by the type underneath rather than by the
  * operation, is recorded at `org.fukuii.evm.Interpreter`'s own branch.
  */
object Eip5656:

  /** The operation joins the table working out its own price. */
  val memoryCopy: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.MCopy, Cost.Computed)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(5656), memoryCopy)
