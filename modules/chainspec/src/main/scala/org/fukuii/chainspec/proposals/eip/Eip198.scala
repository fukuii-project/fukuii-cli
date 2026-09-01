package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-198 -- modular exponentiation over operands of any width, at `0x05`.
  *
  * ==One entry, and it is the first proposal here to reach a precompile set
  * rather than the operation table==
  *
  * *"At address 0x00......05, add a precompile that expects input in the
  * following format"* (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-198.md`, Final).
  * A native has no byte in the instruction set, so nothing about this document
  * touches `org.fukuii.evm.OpcodeTable`; what it changes is which addresses
  * answer without running code, which is the other half of what a chain
  * configuration produces.
  *
  * ==The price the entry is built with is the schedule's, not a figure here==
  *
  * `org.fukuii.evm.Precompile.ModExp` takes its divisor as a constructor
  * argument, so the delta reads it out of the rules it is applied to rather
  * than naming 20. Two things follow. A network stating a different divisor
  * gets its own, without a second proposal. And EIP-2565, which moves that
  * divisor to 3, is a repricing in the schedule plus a rebuild of this entry --
  * the obligation `org.fukuii.evm.GasSchedule` warns about for every price a
  * chain configuration COPIES at the moment it builds an entry.
  *
  * ==What the document says about the price, and what it gets wrong==
  *
  * *"`GQUADDIVISOR: 20`"*, under `# Parameters`. The same document's second
  * worked example then divides by 100 in prose -- *"a 4096-bit RSA
  * exponentiation would cost `mult_complexity(512) * 4095 / 100 = 22853376`
  * gas"* -- and the arithmetic in that sentence settles which of the two is
  * meant: `mult_complexity(512) * 4095` is 457,067,520, and 22,853,376 is that
  * over 20. Over 100 it would be 4,570,675. So the stated result is the
  * divisor's own witness against the divisor written beside it, and the figure
  * under `# Parameters` stands.
  *
  * Three implementations from three language families agree with it and none
  * carries the 100: `ethereum/go-ethereum-pow` @ `v1.10.26` divides by a
  * `big20` in `core/vm/contracts.go`, `besu-eth/besu` @ `fdf1247c6d` declares
  * `GQUADDIVISOR = 20` in `ByzantiumGasCalculator.java`, and
  * `NethermindEth/nethermind` @ `b92e2a4719` divides by 20 in
  * `ModExpPrecompilePreEip2565.cs`. A fourth source states it as a network's
  * configuration rather than as code: `openethereum/parity-ethereum` @
  * `55c90d4016` gives the mainnet builtin at this address as
  * `{"modexp": {"divisor": 20}}`, activated at `0x42ae50`.
  *
  * ==No floor, and that is this fork rather than an omission==
  *
  * The document names no minimum charge and neither does the specification, so
  * the formula's own answer stands however small -- which for the smallest
  * calls this fork admits is nothing at all. The floor of 200 arrives with
  * EIP-2565, in the same document that moves the divisor.
  */
object Eip198:

  /** The native joins the set at the address the document names, priced by the
    * scheme this document defines and from the two figures the rules already
    * state.
    *
    * The floor is read from the record rather than written as a zero here, for
    * the reason the divisor is: a network states its own prices, and this
    * document places the native rather than pricing it. A network holding that
    * floor above zero at its genesis would be one this document has nothing to
    * say about, which is what reading it rather than asserting it preserves.
    */
  val modExp: Proposal =
    rules =>
      rules.copy(precompiles =
        rules.precompiles.adding(
          PrecompileSet.ModExp,
          Precompile.ModExp(
            rules.schedule.precompileModExpDivisor,
            rules.schedule.precompileModExpFloor,
            Precompile.ModExpComplexity.Piecewise
          )
        )
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(198), modExp)
