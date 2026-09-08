package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{BlockRandomness, Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-4399 changes, and the much larger set of things it must
  * leave alone.
  *
  * ==The negative cases carry more weight here than the positive one==
  *
  * The delta is a single assignment, so the assertion that it happened is
  * cheap. What this document is unusual for is everything it does NOT do: the
  * byte stays, the price stays, the table's size stays, and the operation keeps
  * its inputs and outputs. **A delta that added an entry at `0x44`, or repriced
  * the one already there, would satisfy a spec that only checked the new
  * member**, so the cases below name each of those wrong answers.
  */
class Eip4399Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.grayGlacier

  private val adopted: UpgradeRules = base.adopting(Eip4399.component)

  "adopting EIP-4399" should "make the operation at 0x44 report the beacon chain's randomness" in
    assert(
      adopted.evm.blockRandomness == BlockRandomness.Eip4399,
      "the document supplants what the operation returns"
    )

  it should "have reported the block's own difficulty before it was adopted" in
    assert(
      base.evm.blockRandomness == BlockRandomness.Unavailable,
      "the fork below this one already reported randomness, leaving the change untested"
    )

  it should "leave the operation at the byte it was already at" in
    // The document renames the opcode and does not renumber it, and the rename
    // is a SHOULD this build takes on the reading rather than on the byte.
    assert(
      adopted.evm.table.contains(Opcode.Difficulty),
      "the vocabulary entry for 0x44 left the table, so a chain running these rules refuses the byte"
    )

  it should "not change what the operation costs" in
    // "The gas cost of the DIFFICULTY (0x44) opcode remains unchanged", and the
    // executable specification declares the charge at the same tier on the same
    // line of the two consecutive fork modules. A delta that repriced it would
    // be invisible to the member assertion above.
    assert(
      adopted.evm.table.operationAt(0x44).map(_.cost) == base.evm.table.operationAt(0x44).map(_.cost),
      "a document stating the price is unchanged moved it"
    )

  it should "price it at the base tier, which is what unchanged means here" in
    // Stated positively as well, because the case above compares two values
    // that would agree if the delta had moved BOTH of them.
    assert(
      adopted.evm.table.operationAt(0x44).map(_.cost) == Some(Cost.Fixed(adopted.evm.schedule.base)),
      "the operation is priced away from the tier the specification declares it at"
    )

  it should "add no operation and remove none" in
    assert(
      adopted.evm.table.opcodes == base.evm.table.opcodes,
      "a document that supplants a return value changed which operations the chain runs"
    )

  it should "leave every price in the schedule where it found it" in
    assert(adopted.evm.schedule == base.evm.schedule, "a document with no price delta moved a price")

  it should "leave the precompiles where it found them" in
    assert(adopted.evm.precompiles == base.evm.precompiles, "a document with no native delta moved one")

  it should "settle that one member and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(blockRandomness = BlockRandomness.Eip4399),
      "the adopting machine differs from the earlier one by something other than what 0x44 reports"
    )

  it should "leave the consensus facet untouched" in
    // The other document in this upgrade is what reaches consensus and the
    // header. Reference identity rather than equality, so a delta rebuilding an
    // equal record still fails.
    assert(adopted.consensus eq base.consensus, "a machine-only document reached the consensus facet")

  it should "leave the header facet untouched" in
    // The claim worth stating because the document HAS a header half: it
    // requires a producer to write the randomness into a field. Nothing in this
    // build produces a block or validates that field, so the half is real and
    // reaches no rule here.
    assert(adopted.header eq base.header, "a machine-only document reached the header facet")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(ProposalId.Eip(4399)),
      "the journal must record what was adopted"
    )
