package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-196 -- addition and scalar multiplication on `alt_bn128`, at `0x06` and
  * `0x07`.
  *
  * ==One document, TWO entries, and that is the document's own shape==
  *
  * *"add precompiled contracts for point addition (ADD) and scalar
  * multiplication (MUL) on the elliptic curve "alt_bn128" ... Address of ADD:
  * 0x6 ... Address for MUL: 0x7"* (`ethereum/EIPs` @ `dbfa6bee83`,
  * `EIPS/eip-196.md`, Final). A proposal is adopted or it is not, so both
  * entries are one delta: a network taking one of the two would be running
  * something this document does not describe.
  *
  * ==What it does NOT reach==
  *
  * `org.fukuii.evm.OpcodeTable`, for `Eip198`'s reason -- a native has no byte
  * in the instruction set. And EIP-197, which places a third entry at a third
  * address: the two documents are read together and adopted apart, because
  * neither states a dependency on the other and a network could take one alone.
  *
  * ==The prices the entries are built with are the schedule's==
  *
  * *"Gas cost for `ECADD`: 500"* and *"Gas cost for `ECMUL`: 40000"*, under
  * `# Gas costs`. Both are read out of the rules the delta is applied to rather
  * than named here, so EIP-1108 -- which moves them to 150 and 6000 -- is a
  * repricing in the schedule plus a rebuild of these two entries, the
  * obligation `org.fukuii.evm.GasSchedule` warns about for every price a chain
  * configuration COPIES at the moment it builds an entry.
  */
object Eip196:

  /** Both natives join the set at the addresses the document names, priced from
    * the figures the rules already state.
    */
  val curveArithmetic: Proposal =
    rules =>
      rules.copy(precompiles =
        rules.precompiles
          .adding(PrecompileSet.AltBn128Add, Precompile.AltBn128Add(rules.schedule.precompileAltBn128Add))
          .adding(PrecompileSet.AltBn128Mul, Precompile.AltBn128Mul(rules.schedule.precompileAltBn128Mul))
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(196), curveArithmetic)
