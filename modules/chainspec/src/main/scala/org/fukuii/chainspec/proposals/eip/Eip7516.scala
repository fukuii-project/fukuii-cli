package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-7516 -- `BLOBBASEFEE`.
  *
  * ==One entry, at a tier rather than at a price of its own==
  *
  * *"Add a `BLOBBASEFEE` instruction with opcode `0x4a`, with gas cost `2`"*
  * (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-11), `EIPS/eip-7516.md:27-31`,
  * Final), over a table giving the operation zero inputs, one output and a cost
  * of 2. That is the base tier, which `org.fukuii.evm.GasSchedule.base` holds.
  *
  * **Both corroborating sources declare the price AS the tier rather than as a
  * number**, which is why the entry below names the tier for
  * [[Eip3198]]'s reason: `ethereum/execution-specs` @ `0cc100eb1`
  * `src/ethereum/forks/cancun/vm/gas.py:150` is
  * `OPCODE_BLOBBASEFEE: Final[Uint] = BASE` with `BASE` at 2 on `:35`, and
  * `besu-eth/besu` @ `b330564a9`
  * `evm/.../operation/BlobBaseFeeOperation.java:30` constructs the operation
  * with `gasCalculator.getBaseTierGasCost()`. A literal 2 here would opt the
  * operation out of its own tier.
  *
  * ==The document's own worked example is one line long and is the whole of its
  * behavior==
  *
  * *"Assume calling `get_blob_gasprice(header)` ... returns `7 wei`:
  * `BLOBBASEFEE` should push the value `7` (left padded byte32) to the stack"*
  * (`EIPS/eip-7516.md:50-53`), for `0x4a00`, consuming 2 gas. So the operation
  * decides nothing about the value -- it reports what EIP-4844's accounting
  * already fixed, which is why that document and not this one carries the
  * figures.
  *
  * ==This is why a rule set cannot adopt the operation without the accounting==
  *
  * The same relationship [[Eip3198]] records with [[Eip1559]], one fork later
  * and with one more way to be misconfigured. The operation reads two things
  * neither of which this document supplies -- the excess a header states, and
  * the update fraction a fork resolves -- and `org.fukuii.evm.Interpreter`
  * refuses rather than defaulting when either is missing. Zero is not a usable
  * stand-in for either: a zero excess is the ordinary state of the fork's own
  * first block, and a zero fraction divides by zero.
  */
object Eip7516:

  /** The operation joins the table at the tier the document names. */
  val blobBaseFee: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.BlobBaseFee, Cost.Fixed(rules.schedule.base))))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(7516), blobBaseFee)
