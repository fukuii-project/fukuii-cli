package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-1108 -- the `alt_bn128` natives get cheaper.
  *
  * ==Four figures, from the Specification TABLE and not from the arithmetic
  * below it==
  *
  * *"| `ECADD` | `0x06` | 500 | 150 |"*, *"| `ECMUL` | `0x07` | 40 000 | 6 000
  * |"* and *"| Pairing check | `0x08` | 80 000 * k + 100 000 | 34 000 * k + 45
  * 000 |"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1108.md`, Final, under
  * `## Specification`).
  *
  * **The same document says `~35,000 * k + 45,000` a few lines later and that
  * figure is NOT the one to take.** It closes a benchmark derivation -- a
  * measured 1,292 microseconds per pairing at a 'fair' 25.86 gas per
  * microsecond -- and carries the `~` that says so. The table is what the
  * document specifies; the paragraph is how it was arrived at, rounded.
  * `ethereum/execution-specs` @ `20f7f6271a`, `forks/istanbul/vm/gas.py:75`
  * settles it at `PRECOMPILE_ECPAIRING_PER_POINT: Final[Uint] = Uint(34000)`,
  * with the other three at `:72`, `:73` and `:74`.
  *
  * ==Moving the schedule alone would be a COMPLETE silent no-op==
  *
  * A precompile price is read where the entry is BUILT and nowhere else, so
  * nothing consults these four fields again once a set exists. Repricing them
  * and stopping would leave all three natives charging Byzantium's figures with
  * a schedule that says otherwise, and no operation anywhere reading the
  * difference. So the entries are rebuilt, which is what
  * `org.fukuii.evm.PrecompileSet.adding` is for: an entry placed at an address
  * already occupied replaces what was there.
  *
  * `org.fukuii.evm.GasSchedule` states the three classes a price falls into and
  * how to re-derive which is which. This is the third class, and this document
  * is the first in the tree to be in it -- every proposal before this one either
  * installed a native at a price it never moved, or moved a price the table
  * carries.
  *
  * ==The rebuilt entries read from the repriced record, never from `rules`==
  *
  * `Eip150` is the precedent and the shape is deliberate: bind the moved
  * schedule once, then build from it. Reading a moved figure back off
  * `rules.schedule` would take the value from BEFORE the copy and produce a
  * record whose stated prices and whose natives disagree -- which compiles,
  * runs, and is wrong only in what it charges.
  *
  * ==What it does NOT reach==
  *
  * `org.fukuii.evm.OpcodeTable`. None of the three has a byte in the instruction
  * set, and the two curve documents this one reprices place them by address.
  *
  * ==It presupposes the two documents it reprices==
  *
  * *"The gas costs for `ECADD` and `ECMUL` are updates to the costs listed in
  * EIP-196, while the gas costs for the pairing check are updates to the cost
  * listed in EIP-197."* This delta places all three unconditionally rather than
  * testing for them, so a chain composing it without [[Eip196]] and [[Eip197]]
  * would acquire the natives here rather than being left inconsistent. That is
  * the composition the document describes and not a second way to obtain them;
  * a network's schedule is what orders the two, and no network runs this
  * document alone.
  */
object Eip1108:

  /** All four figures move, and all three natives are rebuilt from the moved
    * record so that the entries and the schedule cannot disagree.
    */
  val curveRepricing: Proposal =
    rules =>
      val repriced = rules.schedule.copy(
        precompileAltBn128Add = BigInt(150),
        precompileAltBn128Mul = BigInt(6000),
        precompileAltBn128PairingBase = BigInt(45000),
        precompileAltBn128PairingPerPoint = BigInt(34000)
      )
      rules.copy(
        schedule = repriced,
        precompiles = rules.precompiles
          .adding(PrecompileSet.AltBn128Add, Precompile.AltBn128Add(repriced.precompileAltBn128Add))
          .adding(PrecompileSet.AltBn128Mul, Precompile.AltBn128Mul(repriced.precompileAltBn128Mul))
          .adding(
            PrecompileSet.AltBn128PairingCheck,
            Precompile.AltBn128PairingCheck(
              repriced.precompileAltBn128PairingBase,
              repriced.precompileAltBn128PairingPerPoint
            )
          )
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(1108), curveRepricing)
