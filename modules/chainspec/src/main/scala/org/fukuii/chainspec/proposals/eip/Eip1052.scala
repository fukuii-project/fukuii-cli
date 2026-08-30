package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-1052 -- `EXTCODEHASH`.
  *
  * ==One entry, and its price is a member of its own for a reason this file
  * should state==
  *
  * *"`EXTCODEHASH` ... `0x3f`"*, *"The gas cost of the `EXTCODEHASH` is 400"*
  * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1052.md`, Final). Corroborated at
  * `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/constantinople/vm/gas.py:114`, `OPCODE_EXTCODEHASH: Final[Uint] =
  * Uint(400)`, and at `ethereum/go-ethereum` @ `e9e35a42f8`,
  * `params/protocol_params.go:138`, `ExtcodeHashGasConstantinople = 400`.
  *
  * **400 IS NOT `org.fukuii.evm.GasSchedule.externalBase`, which is 700 at
  * this fork**, and the two are easy to confuse because the semantics push the
  * other way: `EXTCODESIZE` and `EXTCODECOPY` are the other two operations
  * that reach another account's code, this project prices both from
  * `externalBase`, and this is the third of the family. The specification
  * prices it apart from them anyway. `forks/constantinople/vm/gas.py:156`
  * carries `OPCODE_EXTERNAL_BASE = Uint(700)` in the same file as the 400
  * above, so the separation is stated rather than inferred.
  *
  * Reusing `externalBase` here would compile, would read as the semantically
  * tidy choice, and would overcharge every `EXTCODEHASH` by 300 -- silently,
  * because a fixed-price table entry is not checked against anything.
  *
  * ==And it does not become `balance` either, which is 400 at this fork==
  *
  * That coupling would be right by coincidence and wrong by construction: no
  * document ties the two, and no fork in range separates them, so nothing
  * would ever falsify it. `org.fukuii.evm.GasSchedule.extCodeHash` exists so
  * the figure is this operation's own.
  *
  * **The field is read only where the table is built**, so a later repricing
  * has to reach the table as well and moving the schedule alone is a silent
  * no-op. `GasSchedule`'s own scaladoc carries the classification and how to
  * re-derive it. EIP-1884 is the repricing that will need it, taking this
  * figure from 400 to 700 at Istanbul -- so the field arrives already inside
  * that hazard rather than acquiring it later.
  *
  * ==What the operation answers for an EMPTY account is the machine's, and it
  * is not the hash of empty code==
  *
  * Zero, distinctly from the real digest a codeless-but-funded account has.
  * `org.fukuii.evm.Interpreter` carries the test and why it is EIP-161's
  * emptiness rather than the collision rule that reads like it.
  */
object Eip1052:

  /** The operation joins the table at its own price. */
  val extCodeHash: Proposal =
    rules =>
      rules.copy(table = rules.table.adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(rules.schedule.extCodeHash))))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(1052), extCodeHash)
