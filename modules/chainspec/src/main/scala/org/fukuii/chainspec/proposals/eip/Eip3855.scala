package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-3855 -- an operation that pushes zero without an operand.
  *
  * ==One entry, at a tier rather than at a price of its own==
  *
  * *"The instruction `PUSH0` is introduced at `0x5f`. It has no immediate data,
  * pops no items from the stack, and places a single item with the value 0 onto
  * the stack. The cost of this instruction is 2 gas (aka `base`)"*
  * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-3855.md`, Final). The
  * document names the tier itself, in its own parenthesis and again in its
  * Rationale -- *"The `base` gas cost is used for instructions which place
  * constant values onto the stack, such as `ADDRESS`, `ORIGIN`, and so forth"*
  * -- so the entry below is built from `org.fukuii.evm.GasSchedule.base` for the
  * reason [[Eip3198]]'s is: a proposal that moved the base tier would move this
  * operation with it, and a literal 2 would opt the operation out of its own
  * tier.
  *
  * ==It is NOT a member of the push family, and three things go wrong if it is
  * treated as one==
  *
  * The name says push and the byte sits one below `PUSH1`, so the pull toward
  * folding it into that family is strong. The document forecloses it twice
  * over.
  *
  *   - **It reads no operand.** *"jumpdest-analysis is unaffected, as `PUSH0`
  *     has no immediate data bytes"* (Security Considerations). A scan that gave
  *     it an operand width would skip the byte after it, so a `JUMPDEST` there
  *     would stop being a destination -- and the program would fail at a jump
  *     rather than at the operation, which is the wrong place to be told.
  *   - **It is priced a tier below them.** The push family is very low, three
  *     gas; this is base, two. `org.fukuii.evm.OpcodeTable` prices the family
  *     from one predicate, so admitting this operation to it overcharges every
  *     execution by one gas -- silently, with no failure anywhere.
  *   - **`0x5f` is contiguous with the family and that is deliberate rather than
  *     structural.** *"`0x5f` means it is in a 'contiguous' space with the rest
  *     of the `PUSH` implementations and potentially could share the
  *     implementation"* (Rationale) -- *could*, and the sharing the document
  *     contemplates is an implementation's, not a change of what the operation
  *     is.
  *
  * `org.fukuii.evm.Opcode.isPush` therefore excludes it, and states what
  * widening that predicate would cost.
  *
  * ==What it does NOT reach==
  *
  * The schedule. No figure moves -- the tier this entry is built from is already
  * what the rules hold -- so this document adds an operation without repricing
  * anything, the same shape as [[Eip3198]] and [[Eip1344]].
  *
  * ==The value it pushes is a constant, so nothing outside the frame is read==
  *
  * That is what separates it from the two documents whose components read the
  * same way. [[Eip3198]]'s operation reads a header field and [[Eip1344]]'s
  * reads the network's identifier, so each of those depends on something being
  * present to read; this one depends on nothing at all and is expressible over
  * any rule set.
  */
object Eip3855:

  /** The operation joins the table at the tier the document names. */
  val pushZero: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.Push0, Cost.Fixed(rules.schedule.base))))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(3855), pushZero)
