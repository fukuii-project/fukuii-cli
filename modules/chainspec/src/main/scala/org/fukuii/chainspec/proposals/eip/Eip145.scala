package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-145 -- the bitwise shifting instructions.
  *
  * ==Three entries, one price, and the price is one this schedule already
  * names==
  *
  * *"`SHL` (shift left) ... `0x1b`"*, *"`SHR` (logical shift right) ...
  * `0x1c`"* and *"`SAR` (arithmetic shift right) ... `0x1d`"*, each *"of the
  * `verylow` tier"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-145.md`, Final).
  * `ethereum/execution-specs` @ `20f7f6271a` declares all three as
  * `Final[Uint] = VERY_LOW` in `forks/constantinople/vm/gas.py:107-109`, and
  * `ethereum/go-ethereum` @ `e9e35a42f8` gives all three `constantGas:
  * GasFastestStep` in `newConstantinopleInstructionSet()`.
  *
  * So this document introduces no figure. It takes
  * `org.fukuii.evm.GasSchedule.veryLow`, which every network already sets, and
  * a network repricing that tier moves these with it -- which is the correct
  * coupling rather than an incidental one, since the tier is what the document
  * names.
  *
  * ==What the operations DO is the machine's, and the out-of-range answers are
  * the part worth knowing==
  *
  * `org.fukuii.evm.Word.shiftLeft`, `shiftRight` and `shiftRightArithmetic`
  * carry the semantics and the citations. The one thing that does not follow
  * from the names: a shift at or beyond the width is a defined VALUE and not a
  * fault, and for `SAR` that value depends on the sign of the operand rather
  * than being zero. A reader assuming the three saturate alike has two of the
  * three right.
  *
  * ==Adopting these three is not what makes a shift affordable==
  *
  * They enter the table priced from the tier, so a network that adopts this
  * document and then reprices `veryLow` moves them; a network that adopts it
  * and never reprices anything runs them at whatever it launched with. Neither
  * is stated here, because a price this file wrote down would be a second copy
  * of a value the schedule already holds.
  */
object Eip145:

  /** Logical left shift, at the tier the document names. */
  val shiftLeft: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Shl, Cost.Fixed(rules.schedule.veryLow))))

  /** Logical right shift, zero-filling, at the same tier. */
  val shiftRight: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Shr, Cost.Fixed(rules.schedule.veryLow))))

  /** Arithmetic right shift, sign-filling, at the same tier. */
  val shiftRightArithmetic: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Sar, Cost.Fixed(rules.schedule.veryLow))))

  /** Adopting the document, which is adopting all three deltas.
    *
    * The order is immaterial: the three name different bytes, so none can
    * replace another's entry.
    */
  val component: Component = Component.evm(ProposalId.Eip(145), shiftLeft, shiftRight, shiftRightArithmetic)
