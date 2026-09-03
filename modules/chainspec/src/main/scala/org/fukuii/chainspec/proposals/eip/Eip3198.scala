package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-3198 -- `BASEFEE`.
  *
  * ==One entry, at a tier rather than at a price of its own==
  *
  * *"Add a `BASEFEE` opcode at `(0x48)`, with gas cost `G_base`."* (`ethereum/EIPs`
  * @ `dbfa6bee`, `EIPS/eip-3198.md`, Final), over a table giving the operation
  * zero inputs, one output and a cost of 2. `G_base` is the base tier, which
  * `org.fukuii.evm.GasSchedule.base` holds and both networks here state at 2.
  *
  * **The corroborating source declares this operation's price AS the tier rather
  * than as a number**, and the entry below names the tier for the same reason
  * [[Eip1344]]'s does: `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/london/vm/gas.py:138`, `OPCODE_BASEFEE: Final[Uint] = BASE`, with
  * `BASE: Final[Uint] = Uint(2)` at `:33`. A proposal that moved the base tier
  * would move this operation with it; a literal 2 here would silently opt the
  * operation out of its own tier. `ethereum/go-ethereum-pow` @ `v1.10.26` reaches
  * the same figure through the same indirection, `core/vm/eips.go:166` setting
  * `constantGas: GasQuickStep` where `core/vm/gas.go:25` declares that 2.
  *
  * ==What it does NOT reach==
  *
  * The schedule. No figure moves -- the tier this entry is built from is already
  * what the rules hold, so this document adds an operation without repricing
  * anything. The same shape as [[Eip1344]], and the same reason.
  *
  * ==Where the value comes from separates it from [[Eip1344]], which is the near
  * miss worth stating==
  *
  * Both documents add one operation at the base tier that reads something from
  * outside the frame, so the two components are almost the same text. They
  * differ in where the value lives, and that difference is settled by a test
  * `org.fukuii.evm.Environment` already states: a chain id is not on the block
  * context because *"its five members are all read off a header, and no header
  * carries a chain id"*. **A base fee is read off a header**, so it joins them
  * rather than sitting beside the chain id.
  *
  * `ethereum/execution-specs` @ `20f7f6271a` puts it in the same place
  * independently -- `forks/london/vm/instructions/environment.py:548` is
  * `push(evm.stack, U256(evm.message.block_env.base_fee_per_gas))`, reading the
  * block environment rather than any chain configuration.
  *
  * ==This document is the reason a rule set cannot adopt EIP-1559's format
  * without EIP-1559's header rule==
  *
  * The operation reads a member that is absent below the fork filling it, and
  * `org.fukuii.evm.Interpreter` refuses rather than defaulting when it finds
  * nothing there. So a rule set adopting this document over a network whose
  * headers carry no base fee does not quietly push zero -- it stops. That is the
  * same obligation `org.fukuii.execution.BlockProcessor.offered` records for the
  * formats it cannot price, and it is why the two halves of that upgrade are
  * adopted together.
  */
object Eip3198:

  /** The operation joins the table at the tier the document names. */
  val baseFee: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.BaseFee, Cost.Fixed(rules.schedule.base))))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(3198), baseFee)
