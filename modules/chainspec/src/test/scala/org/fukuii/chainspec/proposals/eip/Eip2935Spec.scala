package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2935 changes at the rule-set layer, and where the change
  * had to go.
  *
  * ==The absence assertions are the subject here, not a formality==
  *
  * The document's whole rule-set contribution is one flag, and the interesting
  * question is WHICH record carries it. Its neighbor EIP-4788 adds a pre-execution
  * system call too and is gated on a header facet -- because that document adds
  * a header field and this one does not. A later reader who took the header gate
  * for the pattern would put this flag somewhere a fork could not reach, so the
  * case asserting no header rule moved is the one that pins the decision.
  */
class Eip2935Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip2935.component)

  "adopting EIP-2935" should "have the block record its parent's hash before it runs" in
    assert(
      adopted.execution.recordsParentBlockHash,
      "the call is made at the start of any block where the document is active"
    )

  it should "not have recorded it before it was adopted" in
    assert(
      !base.execution.recordsParentBlockHash,
      "the fork below makes no such call, which is what the flag distinguishes"
    )

  it should "leave every other execution rule alone" in
    assert(
      adopted.execution.copy(recordsParentBlockHash = false) == base.execution,
      "one flag moves and no other settlement rule does"
    )

  it should "move no header rule" in
    // The decision this case exists to pin. The sibling proposal that also adds
    // a pre-execution call IS gated on a header facet, and only because it adds
    // a header field; this document adds none, so that gate cannot carry it and
    // a flag placed there would be read on a record the proposal never touches.
    assert(
      adopted.header == base.header,
      "no header element is added, so no header rule can be what selects the call"
    )

  it should "add no operation" in
    // `BLOCKHASH` is unchanged. What the document adds is the state a reader
    // would need in order to answer beyond the machine's own window -- not a new
    // way to ask, and not a different answer to the existing one.
    assert(
      adopted.evm.table == base.evm.table,
      "the opcode it serves is untouched, and the contract it writes is ordinary code"
    )

  it should "add no transaction form" in
    assert(
      adopted.admission == base.admission,
      "a block makes this call on its own account, so no sender offers anything new"
    )
