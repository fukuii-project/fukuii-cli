package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{BlobSchedule, ProposalId, UpgradeRules}
import org.fukuii.evm.{Cost, Opcode}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-4844 changes, and what it must leave alone.
  *
  * ==The figures are the work here, because nothing else can check them==
  *
  * `org.fukuii.evm.BlobGasPriceSpec` certifies the arithmetic against a
  * published table, and every row of that table states its own denominator --
  * so it passes whatever figure this fork resolves. **This spec is the only
  * place the figure itself is asserted**, and a wrong one is a wrong charge on
  * every blob the network ever carries.
  *
  * Three independent sources agree on the pair below. `ethereum/EIPs` @
  * `d2a64c2d4` (2026-09-11), `EIPS/eip-4844.md:49-53`, Final, gives
  * `TARGET_BLOB_GAS_PER_BLOCK` 393,216 against `GAS_PER_BLOB` `2**17`, which is
  * three blobs, and `BLOB_BASE_FEE_UPDATE_FRACTION` 3,338,477.
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11) `params/config.go:337-342`
  * gives `Target: 3` and `UpdateFraction: 3338477` directly. And a published
  * state fixture for this fork carries its own
  * `config.blobSchedule.Cancun = {target 0x03, max 0x06, baseFeeUpdateFraction
  * 0x32f0ed}`, where `0x32f0ed` is 3,338,477.
  */
class Eip4844Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.shanghai

  private val adopted: UpgradeRules = base.adopting(Eip4844.component)

  "adopting EIP-4844" should "give a header at these rules a blob schedule" in
    assert(
      adopted.header.blobSchedule.contains(BlobSchedule(targetBlobs = BigInt(3), maxBlobs = BigInt(6))),
      "a target of three blobs and a maximum of six, which the fixture's own config states as 0x03 and 0x06"
    )

  it should "give the machine the fraction the charge is derived through" in
    assert(
      adopted.evm.blobBaseFeeUpdateFraction.contains(BigInt(3338477)),
      "3,338,477 -- 0x32f0ed in the fixture's own config, and `UpdateFraction` in go-ethereum's"
    )

  it should "not have carried either before it was adopted" in
    // Without this the two cases above pass against a fork below that already
    // held them, which would leave the delta untested.
    assert(
      base.header.blobSchedule.isEmpty && base.evm.blobBaseFeeUpdateFraction.isEmpty,
      "the fork below accounts for no blob gas and prices none"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(4844)),
      "the journal states which document produced these rules"
    )

  it should "put exactly one operation in the table and take none out" in {
    // The operation reporting a commitment, which this component now carries
    // alongside the accounting. Asserted as the difference rather than as a
    // membership, so an entry added or removed elsewhere in the table moves it.
    val gained = adopted.evm.table.opcodes.diff(base.evm.table.opcodes)
    val lost = base.evm.table.opcodes.diff(adopted.evm.table.opcodes)
    assert(
      gained == Set(Opcode.BlobHash) && lost.isEmpty,
      "gained " + gained.toString + " and lost " + lost.toString
    )
  }

  it should "price that operation at the document's own figure and not at a tier" in
    // The price, and the fact that it is NOT the tier it happens to equal.
    // Asserting `Cost.Fixed(3)` alone would pass against a build that had named
    // `schedule.veryLow`, which is 3 at every fork read for this -- so the
    // second half is what makes the first mean anything, and it would fail the
    // day a fork repriced that tier.
    assert(
      adopted.evm.table.operationAt(Opcode.BlobHash.code).map(_.cost).contains(Cost.Fixed(BigInt(3))) &&
        adopted.evm.schedule.veryLow == BigInt(3),
      "the operation costs 3 as a figure of the document's own, at a fork whose very-low tier coincidentally " +
        "also costs 3: " + adopted.evm.table.operationAt(Opcode.BlobHash.code).toString
    )

  it should "admit exactly one new transaction format" in {
    val gained = adopted.admission.admittedTypes.diff(base.admission.admittedTypes)
    val lost = base.admission.admittedTypes.diff(adopted.admission.admittedTypes)
    assert(
      gained == Set(TransactionType.Blob) && lost.isEmpty &&
        adopted.admission == base.admission.copy(admittedTypes = adopted.admission.admittedTypes),
      "gained " + gained.toString + ", lost " + lost.toString +
        ", and nothing else on the admission facet moved"
    )
  }

  it should "move no price and install no precompile" in
    // The part of the document this component still deliberately does not
    // carry. The point-evaluation precompile at `0x0a` belongs to the same
    // document and is not here, so a precompile set that gained an entry would
    // mean the component reached past what its own documentation claims.
    assert(
      adopted.evm.schedule == base.evm.schedule && adopted.evm.precompiles == base.evm.precompiles,
      "the point-evaluation precompile is the same document's and is not in this component"
    )

  it should "leave the header rules it does not name alone" in
    assert(
      adopted.header == base.header.copy(blobSchedule = adopted.header.blobSchedule),
      "the fee market, the constants and the withdrawals commitment are untouched"
    )

  it should "be the whole of the machine's delta" in
    // TWO members now, and naming both is what keeps this a whole-facet
    // assertion rather than a pair of spot checks: every other member of the
    // machine's rules is compared against the fork below.
    assert(
      adopted.evm == base.evm.copy(
        blobBaseFeeUpdateFraction = adopted.evm.blobBaseFeeUpdateFraction,
        table = adopted.evm.table
      ),
      "two members against every other member of the same facet"
    )

  it should "leave what settles a transaction alone" in
    assert(
      adopted.execution == base.execution && adopted.consensus == base.consensus,
      "nothing here changes what running a block does around the machine"
    )

  "the target" should "be stated in blobs rather than in gas" in
    // The unit is a decision three of four sources take, and getting it wrong
    // is a factor of 131,072. A target stored as gas would read as 393,216 here
    // and the derivation would then multiply it again.
    assert(
      adopted.header.blobSchedule.map(_.targetBlobs).contains(BigInt(3)),
      "three blobs, not 393,216 gas -- the derivation is what multiplies by the per-blob figure"
    )
