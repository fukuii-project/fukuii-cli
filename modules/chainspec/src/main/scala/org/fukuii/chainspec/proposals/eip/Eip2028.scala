package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-2028 -- a non-zero byte of transaction data costs less.
  *
  * ==One figure, and the zero-byte price deliberately stays where it is==
  *
  * *"The gas per non-zero byte is reduced from 68 to 16. Gas cost of zero bytes
  * is unchanged."* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-2028.md`, Final).
  * Corroborated at `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/istanbul/vm/gas.py:81`, `TX_DATA_PER_NON_ZERO: Final[Uint] =
  * Uint(16)`, beside `TX_DATA_PER_ZERO: Final[Uint] = Uint(4)` at `:80` --
  * which this document leaves alone, so the two are read together to see that
  * only one moved.
  *
  * **The ratio between the two is what the document actually changes**, and
  * that is the point of leaving the zero-byte price alone: the reduction is
  * argued from network delay rather than from execution cost, so it reprices
  * what a transaction carries without touching what it does.
  *
  * ==What it does NOT reach, which here is everything except one number==
  *
  * This price is read at the moment it is spent, when a transaction's intrinsic
  * cost is settled before any invocation begins -- not copied into an opcode
  * table entry and not into a precompile. So moving the record IS the whole
  * change, and this document needs none of the rebuilding [[Eip1108]] and
  * [[Eip1884]] do. `org.fukuii.evm.GasSchedule` states the three classes a price
  * falls into; this field is in the first.
  *
  * The operation set, the precompile set and every other charge are untouched.
  */
object Eip2028:

  /** The per-byte figure moves, and nothing is built from it. */
  val callDataRepricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionDataPerNonZeroByte = BigInt(16)))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(2028), callDataRepricing)
