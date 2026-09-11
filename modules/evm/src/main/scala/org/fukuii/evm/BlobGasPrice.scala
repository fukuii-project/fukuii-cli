package org.fukuii.evm

import scala.annotation.tailrec

/** The charge per unit of blob gas a block sets, derived from the excess its
  * header states.
  *
  * ==A derivation and not a header field, which is what separates it from the
  * charge beside it==
  *
  * `org.fukuii.types.BlockHeader.baseFeePerGas` is a value a header STATES and
  * a validator re-derives. Nothing states this one: a header carries
  * `excessBlobGas`, and every layer that needs a price computes it from that
  * field and a figure the fork resolves. So the two look alike at a use site and
  * are opposite in where the number comes from.
  *
  * ==The approximation, and why it is iterative rather than a sum of terms==
  *
  * The intent is `factor * e ** (numerator / denominator)`, and both authorities
  * reach it by a Taylor expansion carrying a scaled accumulator: the first term
  * is `factor * denominator`, each later term is the previous one multiplied by
  * the numerator and divided ONCE by `denominator * i`, the terms are summed
  * until one rounds to zero, and the total is divided by `denominator` at the
  * end. `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11), `EIPS/eip-4844.md:86-94`
  * gives it as `fake_exponential`; `ethereum/execution-specs` @ `0cc100eb1`
  * (2026-09-11), `src/ethereum/utils/numeric.py:177-208` gives the identical
  * body as `taylor_exponential`. **The two names are the same function and
  * neither tree carries the other's spelling**, which is the only reason a
  * reader searching for one of them would conclude this build has neither.
  *
  * **Computing each term independently instead is a DIFFERENT function, and it
  * is the reading a summation formula invites.** A term written
  * `factor * numerator^i / (denominator^i * i!)` and floored on its own drops
  * the scaling the accumulator carries, and the totals diverge. [[BlobGasPriceSpec]]
  * measures it over the 70 excesses it probes -- a zero, sixty-four whole blobs'
  * worth, and the five a published fixture states -- and the two disagree at 46
  * of them, the lowest at 2,359,296 where the accumulator gives 2 and
  * independent terms give 1. **The figure is asserted there rather than stated
  * here**, alongside a control showing the two agree wherever the charge is
  * still at its floor -- which is every excess the certified corpora state, and
  * so the reason the divergence had to be measured rather than assumed visible.
  * A price wrong by one is a settlement wrong on every blob in the block, so the
  * arrangement below is load-bearing rather than a transcription preference.
  *
  * ==The scaling is what makes it worth the shape==
  *
  * Seeding at `factor * denominator` and dividing by `denominator` once at the
  * end keeps `denominator` units of precision through every term, which is how
  * a factor of one survives to produce a price above one at all. Dropping either
  * half collapses the result to the factor.
  */
object BlobGasPrice:

  /** The floor under the charge, which is also the factor the expansion starts
    * from.
    *
    * `ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4844.md:51`
    * (`MIN_BASE_FEE_PER_BLOB_GAS`), and `ethereum/execution-specs` @
    * `0cc100eb1`, `src/ethereum/forks/cancun/vm/gas.py:85`
    * (`BLOB_MIN_GASPRICE`). Both state 1, and neither varies it at any fork
    * that has a blob schedule at all -- which is why it is a constant here and
    * not a member of the schedule the fork resolves.
    */
  val Minimum: BigInt = BigInt(1)

  /** The charge at `excessBlobGas`, under a fork whose schedule sets
    * `updateFraction`.
    *
    * `ethereum/execution-specs` @ `0cc100eb1`
    * `src/ethereum/forks/cancun/vm/gas.py:437-456` composes exactly these three
    * arguments, and `ethereum/go-ethereum` @ `02872e9ef`
    * `consensus/misc/eip4844/eip4844.go:46-47` composes the same three from its
    * own per-fork configuration.
    */
  def at(excessBlobGas: BigInt, updateFraction: BigInt): BigInt =
    taylorExponential(Minimum, excessBlobGas, updateFraction)

  /** `factor * e ** (numerator / denominator)`, approximated as both authorities
    * approximate it.
    *
    * Separate from [[at]] rather than inlined into it because the published
    * vectors for this function carry factors other than one, so a test can only
    * reach them through a surface that takes all three.
    *
    * **`denominator` must be positive.** Zero divides by zero rather than
    * answering, which is the same failure both production clients have and is
    * reachable only from a fork configuration that set no update fraction while
    * declaring a blob schedule.
    */
  def taylorExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt =
    accumulate(BigInt(0), factor * denominator, BigInt(1), numerator, denominator) / denominator

  /** The expansion's terms, summed until one rounds away.
    *
    * The division is by `denominator * step` in one operation. Dividing by the
    * two separately is the arrangement `ethereum/go-ethereum` @ `02872e9ef`
    * takes at `consensus/misc/eip4844/eip4844.go:224-225`, and the two agree
    * because a floor of a floor by positive divisors is the floor by their
    * product -- checked rather than assumed, at every value this build's own
    * spec exercises.
    */
  @tailrec
  private def accumulate(
      total: BigInt,
      term: BigInt,
      step: BigInt,
      numerator: BigInt,
      denominator: BigInt
  ): BigInt =
    if term <= 0 then total
    else accumulate(total + term, term * numerator / (denominator * step), step + 1, numerator, denominator)
