package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1559 changes: a format a block may carry, and the charge a
  * block sets.
  *
  * ==Through [[Eip1559.component]], because the wiring is what is untested==
  *
  * Both deltas are reachable on their own, so a spec calling one directly passes
  * with the component wired to nothing. **This document's wiring carries a
  * failure the others cannot have**: it is the first component to write the
  * header facet at all, so one built from the machine-scoped constructor, or
  * from the admission-only shape [[Eip2930]] uses, would admit the format and
  * set no charge -- which is the configuration
  * `org.fukuii.execution.BlockProcessor` raises on rather than returns, because
  * nothing prices a capped offer without a market.
  *
  * ==What this file does NOT assert==
  *
  * How the charge moves between blocks, which is
  * `org.fukuii.consensus.HeaderValidatorSpec`'s over figures from a published
  * fixture; and what a transaction of the admitted format is charged, which is
  * `org.fukuii.execution.TransactionAdmissionSpec`'s.
  */
class Eip1559Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.berlin

  private val adopted: UpgradeRules = base.adopting(Eip1559.component)

  "adopting EIP-1559" should "let a block carry the capped format" in
    assert(
      adopted.admission.admittedTypes.contains(TransactionType.DynamicFee),
      "the document introduces transaction type 2 and a fork adopting it must carry one"
    )

  it should "not have carried it before it was adopted" in
    assert(
      !base.admission.admittedTypes.contains(TransactionType.DynamicFee),
      "otherwise the assertion above holds for a reason that is not this document"
    )

  it should "keep every format the fork already carried" in
    assert(
      base.admission.admittedTypes.forall(adopted.admission.admittedTypes.contains),
      "a network refusing the untagged format would refuse every transaction it had ever carried"
    )

  it should "give the fork a fee market" in
    assert(adopted.header.feeMarket.isDefined, "the charge a block sets is what the document is for")

  it should "not have had one before it was adopted" in
    assert(
      base.header.feeMarket.isEmpty,
      "a header below the market must state no charge, which is a rule and not an absence"
    )

  it should "state the opening charge the specification does" in
    assert(
      adopted.header.feeMarket.map(_.initialBaseFee.toBigInt).contains(BigInt(1000000000)),
      "execution-specs forks/london/fork.py:76 and go-ethereum-pow's protocol_params agree on it"
    )

  it should "state the elasticity multiplier the specification does" in
    // Read by two rules rather than one: it derives the target a block's gas use
    // is measured against, and it scales the parent's limit at the single block
    // where a market begins.
    assert(
      adopted.header.feeMarket.map(_.elasticityMultiplier).contains(BigInt(2)),
      "forks/london/fork.py:74"
    )

  it should "state the change denominator the specification does" in
    assert(
      adopted.header.feeMarket.map(_.maxChangeDenominator).contains(BigInt(8)),
      "forks/london/fork.py:73"
    )

  it should "record itself among the components adopted" in
    assert(
      adopted.components.contains(ProposalId.Eip(1559)),
      "the journal is what a schedule entry is read from"
    )

  it should "leave the machine alone" in
    // The one part of this upgrade that reaches the machine is EIP-3198, which
    // is a separate document. A component touching the table here would mean
    // the two had been folded together.
    assert(adopted.evm == base.evm, "this document changes no opcode, no price and no rule the machine reads")

  it should "leave the consensus facet alone" in
    assert(
      adopted.consensus == base.consensus,
      "the bomb delay in the same upgrade is EIP-3554's, and composing the two must not blur them"
    )
