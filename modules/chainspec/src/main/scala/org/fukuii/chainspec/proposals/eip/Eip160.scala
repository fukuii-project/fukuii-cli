package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-160 -- what an exponent's bytes cost, and nothing else.
  *
  * ==One number moves, and the number beside it does not==
  *
  * The document's own specification is a single sentence: *"increase the gas
  * cost of EXP from 10 + 10 per byte in the exponent to 10 + 50 per byte in the
  * exponent"* (`ethereum/EIPs` @ `15f61ed0f`, `EIPS/eip-160.md`, Final). So the
  * settled part of the charge stays where it was and only the per-byte part
  * moves, which is worth asserting rather than assuming: the two are one
  * expression at the site that spends them, and a delta moving both would be
  * wrong on every network from this fork onward while still producing a
  * plausible figure.
  *
  * Two implementations that do not derive from one another agree, and each
  * shows the base held still in its own way. `ethereum/execution-specs` @
  * `ccaaaba58` gives `forks/spurious_dragon/vm/gas.py` an
  * `OPCODE_EXP_PER_BYTE: Final[Uint] = Uint(50)` beside an unchanged
  * `OPCODE_EXP_BASE: Final[Uint] = Uint(10)`, and that one line is the ENTIRE
  * difference between that file and the preceding fork's copy of it.
  * `besu-eth/besu` @ `c2addd9424` declares
  * `EXP_OPERATION_BYTE_GAS_COST = 50L` in `SpuriousDragonGasCalculator` and
  * inherits `EXP_OPERATION_BASE_GAS_COST = 10L` from the calculator it extends.
  *
  * ==Why this reaches the schedule alone, where [[Eip150]] could not==
  *
  * [[org.fukuii.evm.GasSchedule]] names three classes a price falls into and
  * states the instrument that re-derives which is which, because the answer is
  * a property of where the machine reads each price today rather than of the
  * field. Re-derived for this one, calibrated on the two that record names:
  * `expPerByte` is read in `Interpreter` and in neither `OpcodeTable` nor
  * `PrecompileSet`, so it is in the first class and editing the record is the
  * whole change.
  *
  * The reason is the operation rather than the accounting. `EXP` computes its
  * charge from the exponent it was handed, so [[org.fukuii.evm.OpcodeTable]]
  * holds `Cost.Computed` for it and there is no number in the table to move --
  * which is the case `Cost` exists to distinguish, and the difference from
  * EIP-150, three of whose four repriced fields had been copied into a table
  * entry when the instruction set was built.
  *
  * ==The number in the reference clients' name for this is not this document's==
  *
  * `ethereum/go-ethereum-pow` @ `v1.10.26` and `ethereumclassic/core-geth` @
  * `4185df450` both call the raised figure `ExpByteEIP158`, and go-ethereum
  * gates the fork on `EIP158Block`. EIP-158 is a different, superseded
  * document; EIP-607 lists 155, 160, 161 and 170 and does not list it. A reader
  * checking this file against either client meets that name first, so it is
  * recorded here rather than left to be re-derived.
  *
  * Which fork each network folds this into is that network's, and [[Eip150]]
  * carries the measured case for why a file here is named for a proposal.
  */
object Eip160:

  /** Each byte of an exponent costs five times what it did.
    *
    * A repricing in place, and the only one this document makes.
    */
  val exponentByteRepricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(expPerByte = BigInt(50)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(160), exponentByteRepricing)
