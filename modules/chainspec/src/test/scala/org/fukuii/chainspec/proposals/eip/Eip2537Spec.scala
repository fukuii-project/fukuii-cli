package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.Bytes
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.evm.{Bls12, Bls12Discounts, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2537 places, and at what prices.
  *
  * ==The all-or-nothing property is the one that matters most==
  *
  * A missing address does not fail loudly: a call to an unplaced precompile
  * succeeds returning empty. And the registered set feeds the warm seed, so a
  * partial adoption also moves gas for transactions that call none of them. Both
  * are asserted, because a count alone would pass over six of seven.
  *
  * ==Prices are checked against the specification's figures, not against besu's
  * vectors==
  *
  * The vector corpus carries a gas column, and comparing against it would test
  * agreement between two transcriptions rather than either against the document.
  * `org.fukuii.evm.Bls12PropSpec` checks the answers; this checks the prices.
  */
class Eip2537Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip2537.component)

  private val placed: Seq[org.fukuii.bytes.Address] =
    Seq(
      PrecompileSet.Bls12G1Add,
      PrecompileSet.Bls12G1Msm,
      PrecompileSet.Bls12G2Add,
      PrecompileSet.Bls12G2Msm,
      PrecompileSet.Bls12Pairing,
      PrecompileSet.Bls12MapFpToG1,
      PrecompileSet.Bls12MapFp2ToG2
    )

  private def bytesOfWidth(width: Int): Bytes = Bytes.fromArray(new Array[Byte](width))

  "adopting EIP-2537" should "place all seven natives" in
    assert(
      placed.forall(address => adopted.evm.precompiles.at(address).isDefined),
      "every one of the seven, since a call to an unplaced address succeeds returning empty"
    )

  it should "have placed none of them before it was adopted" in
    assert(
      placed.forall(address => base.evm.precompiles.at(address).isEmpty),
      "the fork below meets each address as one naming no native"
    )

  it should "place them at a contiguous run from 0x0b" in
    // The addresses are the specification's and are checked as a run, because a
    // gap is the shape a partial adoption takes.
    assert(
      placed.map(address => address.toBytes.last & 0xff) == Seq(0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11),
      "0x0b through 0x11, in the document's own order"
    )

  it should "add exactly seven natives and disturb none of the existing ones" in
    // The warm seed reads the whole registered set, so an eighth address placed
    // by accident would move gas for every transaction at the fork.
    assert(
      adopted.evm.precompiles.addresses.size == base.evm.precompiles.addresses.size + 7 &&
        base.evm.precompiles.addresses.forall(adopted.evm.precompiles.addresses.contains),
      "seven added, none displaced"
    )

  "the flat prices" should "be the figures the specification states" in
    assert(
      adopted.evm.schedule.precompileBls12G1Add == BigInt(375) &&
        adopted.evm.schedule.precompileBls12G2Add == BigInt(600) &&
        adopted.evm.schedule.precompileBls12G1Map == BigInt(5500) &&
        adopted.evm.schedule.precompileBls12G2Map == BigInt(23800),
      "the four that do not depend on the input's width"
    )

  "a first-group multi-exponentiation" should "be priced per pair at the discounted rate" in
    // One pair: the first discount entry is 1000 over a multiplier of 1000, so a
    // single pair is priced at exactly the multiplication price. That is the one
    // size where the discount is invisible, which is why the two below are also
    // asserted.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.Bls12G1Msm)
        .map(_.gasFor(bytesOfWidth(Bls12.G1Width + 32)))
        .contains(BigInt(12000)),
      "one pair at the undiscounted multiplication price"
    )

  it should "apply the discount at a size where it bites" in
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.Bls12G1Msm)
        .map(_.gasFor(bytesOfWidth(2 * (Bls12.G1Width + 32))))
        .contains(BigInt(2) * 12000 * Bls12Discounts.g1For(2) / Bls12Discounts.Multiplier),
      "two pairs, discounted by the table's second entry"
    )

  it should "read the table at k minus one, not at k" in
    // The off-by-one this table invites. Indexing by `k` produces a plausible
    // number at every size, so only a case pinned to a known entry catches it.
    assert(
      Bls12Discounts.g1For(1) == BigInt(1000) && Bls12Discounts.g1For(2) == BigInt(949),
      "the first pair is undiscounted and the second is the table's second entry"
    )

  it should "flatten past the end of the table" in
    assert(
      Bls12Discounts.g1For(129) == Bls12Discounts.G1Max && Bls12Discounts.g1For(1000) == Bls12Discounts.G1Max,
      "every size past the table's 128 entries is held at the last discount"
    )

  "a pairing" should "be priced per pair over a base" in
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.Bls12Pairing)
        .map(_.gasFor(bytesOfWidth(2 * (Bls12.G1Width + Bls12.G2Width))))
        .contains(BigInt(37700) + BigInt(32600) * 2),
      "two pairings, which is the base plus twice the per-pair figure"
    )

  "an input that is not a whole number of pairs" should "be priced at nothing and refused" in
    // The two halves of one rule: a width with no `k` has no price, and must not
    // then reach a backend that might answer anyway.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.Bls12G1Msm)
        .map(native => (native.gasFor(bytesOfWidth(100)), native.run(bytesOfWidth(100)).isLeft))
        .contains((BigInt(0), true)),
      "a ragged width is refused rather than priced at something"
    )

  "an empty input" should "be refused rather than treated as an identity" in
    assert(
      adopted.evm.precompiles.at(PrecompileSet.Bls12Pairing).exists(_.run(Bytes.Empty).isLeft),
      "an empty multi-exponentiation is an error in this document, not a trivial answer"
    )
