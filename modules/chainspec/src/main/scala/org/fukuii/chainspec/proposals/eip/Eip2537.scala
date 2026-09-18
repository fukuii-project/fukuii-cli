package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{EvmRules, GasSchedule, Precompile, PrecompileSet}

/** EIP-2537 -- the BLS12-381 curve, as seven natives.
  *
  * ==Seven at once, and a partial set is wrong in TWO ways==
  *
  * The obvious one: a call to an address this build has not placed does not
  * fail. `org.fukuii.evm.PrecompileSet.at` answers an option, and an
  * unregistered address behaves as an ordinary account holding no code -- so the
  * call SUCCEEDS returning empty, and a contract reading that as an answer
  * proceeds on nothing.
  *
  * The quiet one, which is worse: **the set of registered addresses is an input
  * to gas accounting for every transaction at the fork, including transactions
  * that call none of them.** A precompile's address is warm from the start of
  * every transaction where the fork meters state access as warm and cold, so
  * placing six of seven changes what unrelated transactions are charged.
  * `.claude/protocols/consensus-change.md` carries that as a standing fact.
  *
  * So this document's component places all seven or the fork does not adopt it.
  *
  * ==The prices, and why two of them are not separate figures==
  *
  * Six flat prices and a pairing priced per pair over a base. The two
  * multi-exponentiations have no price of their own: the document prices them at
  * the corresponding single-multiplication price scaled by a per-size discount,
  * so what the rule set holds is the multiplication price and
  * `org.fukuii.evm.Bls12Discounts` holds the scaling.
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/vm/gas.py:81-86` gives the six; the pairing's
  * `32600` per pair over `37700` is written at
  * `.../precompiled_contracts/bls12_381/bls12_381_pairing.py:45`, as literals
  * rather than as named constants.
  *
  * ==The addresses are a contiguous run, and that is checked rather than
  * assumed==
  *
  * `0x0b` through `0x11`, in the document's order, immediately after the point
  * evaluation the fork below placed at `0x0a`. Verified against
  * `.../precompiled_contracts/__init__.py:47-53`.
  *
  * ==What this does NOT decide==
  *
  * Which backend computes them. `org.fukuii.evm.Bls12` is the one seam between
  * this document and a native implementation, and the artifact behind it is a
  * dependency decision rather than a rule-set one.
  */
object Eip2537:

  /** The document's eight figures. */
  val curvePricing: GasSchedule => GasSchedule =
    _.copy(
      precompileBls12G1Add = BigInt(375),
      precompileBls12G1Mul = BigInt(12000),
      precompileBls12G1Map = BigInt(5500),
      precompileBls12G2Add = BigInt(600),
      precompileBls12G2Mul = BigInt(22500),
      precompileBls12G2Map = BigInt(23800),
      precompileBls12PairingBase = BigInt(37700),
      precompileBls12PairingPerPair = BigInt(32600)
    )

  /** All seven, placed together.
    *
    * **One function rather than seven, because they are not separately
    * adoptable.** Splitting them would make a partial adoption expressible, and
    * a partial adoption is the defect this document's whole registration exists
    * to prevent.
    */
  val curveNatives: EvmRules => EvmRules =
    rules =>
      val schedule = rules.schedule
      rules.copy(precompiles =
        rules.precompiles
          .adding(PrecompileSet.Bls12G1Add, Precompile.Bls12G1Add(schedule.precompileBls12G1Add))
          .adding(PrecompileSet.Bls12G1Msm, Precompile.Bls12G1Msm(schedule.precompileBls12G1Mul))
          .adding(PrecompileSet.Bls12G2Add, Precompile.Bls12G2Add(schedule.precompileBls12G2Add))
          .adding(PrecompileSet.Bls12G2Msm, Precompile.Bls12G2Msm(schedule.precompileBls12G2Mul))
          .adding(
            PrecompileSet.Bls12Pairing,
            Precompile.Bls12Pairing(schedule.precompileBls12PairingBase, schedule.precompileBls12PairingPerPair)
          )
          .adding(PrecompileSet.Bls12MapFpToG1, Precompile.Bls12MapFpToG1(schedule.precompileBls12G1Map))
          .adding(PrecompileSet.Bls12MapFp2ToG2, Precompile.Bls12MapFp2ToG2(schedule.precompileBls12G2Map))
      )

  /** Adopting the document, which is adopting its prices and then its seven
    * natives -- in that order, because each native is built from a price this
    * rule set has just written.
    */
  val component: Component =
    Component(
      ProposalId.Eip(2537),
      rules => rules.copy(evm = curveNatives(rules.evm.copy(schedule = curvePricing(rules.evm.schedule))))
    )
