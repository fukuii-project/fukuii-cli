package org.fukuii.evm

import org.scalatest.flatspec.AnyFlatSpec

/** The expansion the blob charge is derived through, against a published table
  * of expected outputs rather than against this build's own reading.
  *
  * ==Why a table from a client and not values computed here==
  *
  * The function is purely answering: it returns a number for every input and has
  * no state in which it declines, so a wrong implementation is the same shape as
  * a right one and no self-consistency check can separate them.
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11) publishes fifteen
  * `(factor, numerator, denominator) -> expected` rows in
  * `consensus/misc/eip4844/eip4844_test.go:151-176`, which is an expectation
  * this build had no part in forming. The algorithm itself is transcribed from
  * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11)
  * `src/ethereum/utils/numeric.py:177-208`, so the two sides of every row below
  * come from different trees.
  *
  * ==The rows carry factors other than one, which is why the surface is the
  * three-argument one==
  *
  * Eleven of the fifteen do. [[BlobGasPrice.at]] fixes the factor at the
  * minimum charge, so a spec reaching only through it could exercise none of
  * them -- and the factor is exactly the argument the scaling depends on.
  *
  * ==What this spec CANNOT see==
  *
  * Whether the update fraction a fork resolves is the right number. Every row
  * here states its own denominator, so the whole table passes against a build
  * whose Cancun schedule names some other figure entirely. That is
  * `org.fukuii.chainspec.proposals.eip.Eip4844Spec`'s, and the two are separate
  * for that reason.
  */
class BlobGasPriceSpec extends AnyFlatSpec:

  /** `ethereum/go-ethereum` @ `02872e9ef`
    * `consensus/misc/eip4844/eip4844_test.go:151-176`, transcribed in the order
    * that file states them, with its own comments carried as the trailing note.
    */
  private val Published: Vector[(BigInt, BigInt, BigInt, BigInt, String)] =
    Vector(
      (BigInt(1), BigInt(0), BigInt(1), BigInt(1), "a zero exponent answers the factor"),
      (BigInt(38493), BigInt(0), BigInt(1000), BigInt(38493), "a zero exponent answers the factor, whatever it is"),
      (BigInt(0), BigInt(1234), BigInt(2345), BigInt(0), "a zero factor answers zero"),
      (BigInt(1), BigInt(2), BigInt(1), BigInt(6), "approximates 7.389"),
      (BigInt(1), BigInt(4), BigInt(2), BigInt(6), "the same ratio through a wider pair"),
      (BigInt(1), BigInt(3), BigInt(1), BigInt(16), "approximates 20.09"),
      (BigInt(1), BigInt(6), BigInt(2), BigInt(18), "the same ratio, and NOT the same answer"),
      (BigInt(1), BigInt(4), BigInt(1), BigInt(49), "approximates 54.60"),
      (BigInt(1), BigInt(8), BigInt(2), BigInt(50), "the same ratio, and again not the same answer"),
      (BigInt(10), BigInt(8), BigInt(2), BigInt(542), "approximates 540.598"),
      (BigInt(11), BigInt(8), BigInt(2), BigInt(596), "approximates 600.58"),
      (BigInt(1), BigInt(5), BigInt(1), BigInt(136), "approximates 148.4"),
      (BigInt(1), BigInt(5), BigInt(2), BigInt(11), "approximates 12.18"),
      (BigInt(2), BigInt(5), BigInt(2), BigInt(23), "approximates 24.36"),
      (BigInt(1), BigInt(50000000), BigInt(2225652), BigInt(5709098764L), "a realistic pair, and the widest row")
    )

  /** The scaled form both authorities take, rewritten with the two divisions
    * `ethereum/go-ethereum` @ `02872e9ef`
    * `consensus/misc/eip4844/eip4844.go:215-227` performs separately.
    *
    * Present so the equivalence the production code's own documentation claims
    * is measured rather than asserted from an identity nobody checked.
    */
  private def throughTwoDivisions(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    def loop(total: BigInt, term: BigInt, step: BigInt): BigInt =
      if term <= 0 then total
      else loop(total + term, term * numerator / denominator / step, step + 1)
    loop(BigInt(0), factor * denominator, BigInt(1)) / denominator

  /** The reading a summation formula invites: each term computed and floored on
    * its own, with no accumulator carrying the scaling between them.
    *
    * Here as the NEGATIVE control for the arrangement above. Without it the
    * published table would be evidence that this build computes something, and
    * not that it computes the one of two plausible things the network runs.
    */
  private def throughIndependentTerms(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    def loop(total: BigInt, power: BigInt, denominatorPower: BigInt, factorial: BigInt, step: Int): BigInt =
      val term = factor * power / (denominatorPower * factorial)
      if step > 0 && term <= 0 then total
      else loop(total + term, power * numerator, denominatorPower * denominator, factorial * (step + 1), step + 1)
    loop(BigInt(0), BigInt(1), BigInt(1), BigInt(1), 0)

  /** Blob-gas quantities across the range a chain actually reaches, plus the
    * five a published state fixture states.
    */
  private val Excesses: Vector[BigInt] =
    (BigInt(0) +: (1 to 64).map(step => BigInt(step) * (BigInt(1) << 17)).toVector) ++
      Vector(BigInt(0x080000), BigInt(0x0e0000), BigInt(0x140000), BigInt(0x240000), BigInt(0x360000))

  private val UpdateFraction: BigInt = BigInt(3338477)

  "the expansion" should "answer every published row" in
    assert(
      Published.forall((factor, numerator, denominator, expected, _) =>
        BlobGasPrice.taylorExponential(factor, numerator, denominator) == expected
      ),
      "measured: " + Published
        .filterNot((f, n, d, e, _) => BlobGasPrice.taylorExponential(f, n, d) == e)
        .map((f, n, d, e, note) =>
          f.toString + "," + n.toString + "," + d.toString + " -> " +
            BlobGasPrice.taylorExponential(f, n, d).toString + " want " + e.toString + " (" + note + ")"
        )
        .mkString("; ")
    )

  it should "have a table that discriminates, which a single row could not" in
    // The calibration for the case above. Two rows sharing a ratio and
    // disagreeing about the answer -- 2/1 against 4/2, and 3/1 against 6/2 --
    // are what rule out an implementation that reduces the fraction first, and
    // an all-ones table would rule out nothing at all.
    assert(
      Published.map(_._4).distinct.length == 14 && Published.map((f, n, d, _, _) => (f, n, d)).distinct.length == 15,
      "fifteen distinct inputs and fourteen distinct outputs, so no constant answer passes"
    )

  "the two divisions one client performs separately" should "agree with the single division at every step" in
    // The production code's own documentation claims this equivalence. Measured
    // here across the whole range rather than taken from the arithmetic
    // identity, because the identity holds only for positive divisors and
    // nothing in the signature enforces that.
    assert(
      Excesses.forall(excess =>
        throughTwoDivisions(BlobGasPrice.Minimum, excess, UpdateFraction) ==
          BlobGasPrice.at(excess, UpdateFraction)
      ) && Published.forall((factor, numerator, denominator, expected, _) =>
        throughTwoDivisions(factor, numerator, denominator) == expected
      ),
      "a divergence here would mean this build and go-ethereum price blob gas differently"
    )

  "summing independent terms instead" should "reach a DIFFERENT charge, which is why the accumulator is not optional" in {
    val moved = Excesses.filter(excess =>
      throughIndependentTerms(BlobGasPrice.Minimum, excess, UpdateFraction) != BlobGasPrice.at(excess, UpdateFraction)
    )
    assert(
      moved.length == 46 && moved.min == BigInt(2359296),
      "measured at 46 of " + Excesses.length.toString + " probed excesses, lowest divergence at 2,359,296: " +
        moved.length.toString + " moved, lowest " + moved.minOption.map(_.toString).getOrElse("none")
    )
  }

  it should "agree with the accumulator wherever the charge is still at its floor" in
    // The control for the case above: the two forms are NOT different
    // everywhere, so a build that had swapped them would pass every corpus whose
    // excess is small. That is the whole of why the divergence had to be
    // measured rather than assumed visible.
    assert(
      throughIndependentTerms(BlobGasPrice.Minimum, BigInt(0), UpdateFraction) == BigInt(1) &&
        throughIndependentTerms(BlobGasPrice.Minimum, BigInt(0x140000), UpdateFraction) == BigInt(1),
      "both forms answer the minimum at a zero excess and at the largest the certified corpora state"
    )

  "the charge" should "be the minimum at a zero excess" in
    assert(BlobGasPrice.at(BigInt(0), UpdateFraction) == BlobGasPrice.Minimum, "the fork's own first block")

  it should "stay at the minimum until the excess passes a measured point, and rise by one there" in
    // An exact boundary rather than a range, for the reason the header tier
    // asserts an exact step count: a bound tolerates an implementation that is
    // off by a little, and off by a little is a different charge on every blob.
    assert(
      BlobGasPrice.at(BigInt(2314057), UpdateFraction) == BigInt(1) &&
        BlobGasPrice.at(BigInt(2314058), UpdateFraction) == BigInt(2),
      "measured: the charge is 1 at 2,314,057 and 2 at 2,314,058 under this fork's update fraction"
    )

  it should "never fall below the minimum at any excess" in
    assert(
      Excesses.forall(excess => BlobGasPrice.at(excess, UpdateFraction) >= BlobGasPrice.Minimum),
      "the factor is the floor, and an expansion that rounded it away would price blob gas at nothing"
    )

  it should "rise with the excess and never fall" in
    assert(
      Excesses.sorted.sliding(2).forall {
        case Vector(lower, higher) =>
          BlobGasPrice.at(lower, UpdateFraction) <= BlobGasPrice.at(higher, UpdateFraction)
        case _ => true
      },
      "a charge that fell as the chain ran further over target would invert the mechanism's whole purpose"
    )

  "a later fork's update fraction" should "price the same excess lower" in
    // The property that makes the fraction a member of a fork's rules rather
    // than a constant: the same chain state is charged differently under the
    // figure a later fork resolves. 5,007,716 is the one two sources give for
    // the fork after this one.
    assert(
      BlobGasPrice.at(BigInt(6000000), BigInt(5007716)) < BlobGasPrice.at(BigInt(6000000), UpdateFraction),
      "a wider fraction flattens the curve, so a schedule read from the wrong fork is a wrong charge"
    )
