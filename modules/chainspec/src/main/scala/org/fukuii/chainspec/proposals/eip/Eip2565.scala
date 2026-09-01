package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-2565 -- modular exponentiation repriced, and the reader who calls that a
  * repricing has read one of its three changes.
  *
  * ==THREE CHANGES, AND ONLY ONE OF THEM IS A NUMBER MOVING IN PLACE==
  *
  * The document's Specification is one function, and every line of it differs
  * from [[Eip198]]'s (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2565.md`, Final):
  *
  * {{{
  * def calculate_multiplication_complexity(base_length, modulus_length):
  *     max_length = max(base_length, modulus_length)
  *     words = math.ceil(max_length / 8)
  *     return words**2
  * ...
  *     return max(200, math.floor(multiplication_complexity * iteration_count / 3))
  * }}}
  *
  * A new difficulty term, a divisor of 3 where the earlier document gives 20,
  * and a floor of 200 where the earlier document has none. Two are figures this
  * network states in its own record; the third is a scheme and cannot be, which
  * is why `org.fukuii.evm.Precompile.ModExpComplexity` exists.
  *
  * `ethereum/execution-specs` @ `20f7f6271` reaches all three independently at
  * `forks/berlin/vm/precompiled_contracts/modexp.py` -- `words ** 2` at
  * `:87-88`, `GQUADDIVISOR = Uint(3)` at `:21`, `return max(Uint(200), cost)` at
  * `:166` -- and `besu-eth/besu` @ `fdf1247c6` a third time in
  * `BerlinGasCalculator.java:244-281`, whose `modExpGasCost` squares
  * `(Math.max(modulusLength, baseLength) + 7L) / 8L`, divides by 3 and returns
  * `Math.max(gasRequirement, 200L)`.
  *
  * **`besu-eth/besu-etc` is not a fourth reading of the formula.** That tree's
  * gas calculators are besu's, so what it can corroborate independently is which
  * calculator a network installs at which upgrade, never what the calculator
  * computes.
  *
  * ==What the exponent term does NOT do is move==
  *
  * The squarings an exponent implies are counted exactly as [[Eip198]] counts
  * them, floored at one by the same clause. Both implementations above keep it
  * -- `modexp.py:129` is `return max(count, Uint(1))` and besu takes
  * `Math.max(adjustedExponentLength, 1L)` -- so a reader diffing the two
  * documents' formulas finds three changes and not four.
  *
  * ==A second class is how two clients express this, and this network expresses
  * it as data==
  *
  * `NethermindEth/nethermind` @ `b92e2a471` ships
  * `ModExpPrecompilePreEip2565.cs` beside `ModExpPrecompile.cs`, with a vector
  * file for each; besu overrides `modExpGasCost` per calculator generation. Both
  * are one implementation replaced by another. Here the entry is rebuilt at the
  * same address from a record that now states which scheme, which divisor and
  * which floor -- so two networks running this document compare equal by value,
  * which a pair of classes would also give and a function member would not.
  */
object Eip2565:

  /** Both figures move, and the entry is rebuilt from the moved record under the
    * scheme this document defines.
    *
    * Rebuilt rather than repriced in place for the reason
    * `org.fukuii.evm.GasSchedule` states: a precompile's price is COPIED into
    * its entry when that entry is built, so a delta editing the record alone
    * would move the stated figures and charge the old ones.
    */
  val modExpRepricing: Proposal =
    rules =>
      val repriced = rules.schedule.copy(
        precompileModExpDivisor = BigInt(3),
        precompileModExpFloor = BigInt(200)
      )
      rules.copy(
        schedule = repriced,
        precompiles = rules.precompiles.adding(
          PrecompileSet.ModExp,
          Precompile.ModExp(
            repriced.precompileModExpDivisor,
            repriced.precompileModExpFloor,
            Precompile.ModExpComplexity.SquaredWordCount
          )
        )
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(2565), modExpRepricing)
