package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-152 -- the BLAKE2 compression function `F`, at `0x09`.
  *
  * ==One entry, and its price is per ROUND rather than per call or per byte==
  *
  * *"We propose adding a precompiled contract at address `0x09` wrapping the
  * BLAKE2 `F` compression function"*, and *"Each operation will cost `GFROUND *
  * rounds` gas, where `GFROUND = 1`"* (`ethereum/EIPs` @ `dbfa6bee`,
  * `EIPS/eip-152.md`, Final). Corroborated at `ethereum/execution-specs` @
  * `20f7f6271a`, `forks/istanbul/vm/gas.py:71`,
  * `PRECOMPILE_BLAKE2F_PER_ROUND: Final[Uint] = Uint(1)`.
  *
  * **The multiplier is a number the CALLER writes**, taken from the argument's
  * first four bytes rather than from its length -- which makes this the only
  * native here whose charge is not bounded by how much was sent.
  * `org.fukuii.evm.Precompile.Blake2f` carries what reading that count wrongly
  * costs, and why it is never narrowed to fit a machine word.
  *
  * ==What it does NOT reach==
  *
  * `org.fukuii.evm.OpcodeTable`, for `Eip198`'s reason -- a native has no byte
  * in the instruction set. And no price moves: `GFROUND` is already
  * `org.fukuii.evm.GasSchedule.precompileBlake2fPerRound`, stated at 1 by both
  * networks this repository configures, so the entry is built from a figure the
  * rules already hold rather than from one this document has to install.
  */
object Eip152:

  /** The native joins the set at the address the document names, priced from
    * the per-round figure the rules already state.
    */
  val blake2fCompression: Proposal =
    rules =>
      rules.copy(precompiles =
        rules.precompiles.adding(
          PrecompileSet.Blake2f,
          Precompile.Blake2f(rules.schedule.precompileBlake2fPerRound)
        )
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(152), blake2fCompression)
