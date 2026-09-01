package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.Bytes
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.evm.{Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2565 changes, and the two changes beside the divisor that a
  * reader calling it a repricing would leave out.
  *
  * ==Through [[Eip2565.component]], because the wiring is what is untested==
  *
  * Each delta is reachable on its own, so a spec calling one directly passes
  * with the component wired to nothing.
  *
  * ==The charges are asserted against figures worked out by hand, not against
  * the entry's own arithmetic==
  *
  * A case comparing the adopted entry to a second entry built the same way
  * agrees however wrong both are. The three cases below state what a named input
  * must cost, derived from the document's own function and checkable by anyone
  * holding it.
  */
class Eip2565Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.muirGlacier

  private val adopted: UpgradeRules = base.adopting(Eip2565.component)

  /** What the entry at the modular-exponentiation address charges for `input`. */
  private def charged(rules: UpgradeRules, input: Bytes): BigInt =
    rules.evm.precompiles.at(PrecompileSet.ModExp).map(_.gasFor(input)).getOrElse(fail("no entry at that address"))

  /** The three declared lengths and the operands, as the input lays them out. */
  private def modExpInput(baseLength: Int, exponentLength: Int, modulusLength: Int, operands: String): Bytes =
    def word(value: Int): String = "%064x".format(value)
    Bytes
      .fromHex("0x" + word(baseLength) + word(exponentLength) + word(modulusLength) + operands)
      .getOrElse(fail("the input this case is written around is not hex"))

  /** One base byte, one exponent byte of 0xff, one modulus byte.
    *
    * The difficulty term is the same under both schemes here -- one byte is one
    * eight-byte word, and one squared is one, against `1 * 1` piecewise -- so
    * this input separates the DIVISOR and the FLOOR while holding the third
    * change still.
    */
  private val smallest: Bytes = modExpInput(1, 1, 1, "01" + "ff" + "01")

  /** A base and a modulus of 64 bytes, with an exponent wide enough that the
    * charge clears the floor under both divisors.
    *
    * Under the piecewise scheme 64 bytes gives 64 * 64 = 4096; under the
    * squared-word-count scheme it gives (64 + 7) / 8 = 8, squared = 64. So this
    * input separates the SCHEME, and it does so by a factor no divisor change
    * could produce.
    */
  private val sixtyFour: Bytes =
    modExpInput(64, 32, 64, "ff" * 64 + "ff" * 32 + "ff" * 64)

  "adopting EIP-2565" should "divide by three" in
    assert(
      adopted.evm.schedule.precompileModExpDivisor == BigInt(3),
      "the document's own calculate_gas_cost divides the product by 3"
    )

  it should "have divided by twenty before it was adopted" in
    assert(
      base.evm.schedule.precompileModExpDivisor == BigInt(20),
      "EIP-198's divisor is what this document moves"
    )

  it should "put a floor of two hundred under the charge" in
    assert(
      adopted.evm.schedule.precompileModExpFloor == BigInt(200),
      "the document returns max(200, ...)"
    )

  it should "have had no floor before it was adopted" in
    assert(
      base.evm.schedule.precompileModExpFloor == BigInt(0),
      "EIP-198 names no minimum, and a floor of nothing is how that is stated"
    )

  it should "charge the floor for an input whose formula gives less" in
    // The one change no divisor can express: the smallest input this precompile
    // admits works out to well under two hundred under either divisor, so what
    // it costs afterwards is decided by the floor alone.
    assert(
      charged(adopted, smallest) == BigInt(200),
      "an input the formula prices below the floor must be charged the floor"
    )

  it should "have charged that same input less than the floor before it was adopted" in
    // The control the case above needs: without it, an entry that ignored the
    // floor and happened to price this input at exactly 200 would pass.
    assert(
      charged(base, smallest) < BigInt(200),
      "the floor is not doing any work if the earlier entry already charged it"
    )

  it should "count the difficulty term in eight-byte words rather than piecewise" in
    // 64 bytes is 8 whole words, so the term is 64 where the earlier scheme
    // gives 64 * 64 = 4096 -- a factor of 64, which no change of divisor from 20
    // to 3 could produce.
    assert(
      charged(adopted, sixtyFour) == BigInt(64) * BigInt(255) / BigInt(3),
      "the difficulty term is (ceil(max_length / 8))**2"
    )

  it should "have counted that same input's difficulty term piecewise before it was adopted" in
    assert(
      charged(base, sixtyFour) == BigInt(64) * BigInt(64) * BigInt(255) / BigInt(20),
      "EIP-198's mult_complexity squares the byte length below 64"
    )

  it should "rebuild the entry rather than only moving the record" in
    // A precompile's price is copied into its entry when the entry is built, so
    // a delta editing the schedule alone would state 3 and charge 20. Asserted
    // by comparing what the entry charges against what the record now says,
    // which is the one comparison a half-applied delta fails.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.ModExp)
        .contains(
          Precompile.ModExp(BigInt(3), BigInt(200), Precompile.ModExpComplexity.SquaredWordCount)
        ),
      "the entry still holds the figures and the scheme it was built with before this document"
    )

  it should "leave every other precompile exactly where it found it" in
    assert(
      adopted.evm.precompiles.addresses == base.evm.precompiles.addresses &&
        (base.evm.precompiles.addresses - PrecompileSet.ModExp).forall { address =>
          adopted.evm.precompiles.at(address) == base.evm.precompiles.at(address)
        },
      "a document about one native reached another"
    )

  it should "leave the operation table untouched" in
    assert(adopted.evm.table eq base.evm.table, "a precompile repricing must not reach the machine's operations")

  it should "leave every other price in the record where it found it" in
    assert(
      adopted.evm.schedule ==
        base.evm.schedule.copy(precompileModExpDivisor = BigInt(3), precompileModExpFloor = BigInt(200)),
      "the delta moved a price this document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(2565)), "the journal must record what was adopted")
