package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}

/** EIP-3529 -- what a transaction may hand back, cut in three places at once.
  *
  * ==Three changes, and the document numbers them itself==
  *
  * *"1. Remove the `SELFDESTRUCT` refund. 2. Replace `SSTORE_CLEARS_SCHEDULE`
  * ... with `SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST` (4,800 gas ...)
  * 3. Reduce the max gas refunded after a transaction to `gas_used //
  * MAX_REFUND_QUOTIENT`"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-3529.md`,
  * Final), with `MAX_REFUND_QUOTIENT` stated as 5 in the parameter table above
  * it.
  *
  * `ethereum/execution-specs` @ `20f7f6271a` reaches all three independently:
  * `forks/london/vm/gas.py:62` is `REFUND_STORAGE_CLEAR: Final[int] = 4800`
  * against `forks/berlin/vm/gas.py:62`'s `15000`; `REFUND_SELF_DESTRUCT` is
  * declared in that Berlin file and in the London one is declared nowhere; and
  * `forks/london/fork.py:813` divides by 5 where `forks/berlin/fork.py:698`
  * divides by 2. `ethereum/go-ethereum-pow` @ `v1.10.26` states the last as two
  * named constants side by side, `RefundQuotient = 2` and
  * `RefundQuotientEIP3529 = 5` (`params/protocol_params.go:158-159`).
  *
  * ==This is the first document to write the execution facet in a long while,
  * and the reason is that the divisor was not a rule==
  *
  * The first two changes are prices and land on the schedule. The third had
  * nowhere to land: the divisor was written into the settlement arithmetic as a
  * literal, so no proposal could reach it. Nothing before this document needed
  * to, which is why the gap survived every earlier fork.
  *
  * ==The document's own arithmetic for 4,800 depends on two proposals already
  * being in force==
  *
  * `SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST` is EIP-2929's reset charge
  * plus EIP-2930's per-key cost. The figure is stated outright as well, and the
  * record holds the figure -- but the dependency is real and is why this
  * document declares `requires: 2200, 2929, 2930`. **It does not require
  * EIP-1559**, which matters because the two arrive at the same upgrade and the
  * fee market is the half a downstream network may decline while taking this
  * one.
  *
  * ==Only ONE of the three storage-refund figures moves==
  *
  * [[Eip2929]] left `refundNetStorageClear` in place and said so, naming this
  * document as the one that moves it. The two beside it --
  * `refundNetStorageResetFromZero` and `refundNetStorageReset` -- do NOT move,
  * and that is measurable rather than a reading: the storage instructions'
  * refund arithmetic is byte-identical between the two forks' modules, so the
  * formulas are unchanged and only the constant is different.
  *
  * **`refundStorageClear` also holds 15,000 and is NOT this document's.** That
  * is the legacy metering field, reachable only where a network still meters
  * storage the pre-EIP-2200 way; this network does not, so moving it would state
  * a price nothing spends. A network still on legacy metering would need the
  * other field, so a downstream adoption of this document is not the same delta
  * -- which is the same reasoning [[Eip2929]] applied to its own untouched
  * reset field.
  *
  * ==Removing the refund and setting it to zero are the same thing here, and
  * that is not true of every removal==
  *
  * The counter this feeds is a signed arbitrary-precision total, so adding zero
  * to it is indistinguishable from not adding to it. **This project has a
  * standing case running the other way** -- a block reward of zero and no block
  * reward are different state roots, because a credit brings an account into
  * being where an omission does not, and `ConsensusRules` carries a separate
  * member for exactly that. The asymmetry is real and does not transfer:
  * arithmetic on a counter has no such side effect.
  */
object Eip3529:

  /** The clearing refund, after the document replaces it.
    *
    * Stated as the figure the document states, rather than computed from the
    * two proposals it derives it from. Both readings give 4,800 here; deriving
    * it would make this record depend on two other records agreeing, which is a
    * dependency the document does not impose on an implementation.
    */
  val ClearingRefund: BigInt = BigInt(4800)

  /** The divisor bounding what a transaction hands back. */
  val MaxRefundQuotient: BigInt = BigInt(5)

  val component: Component =
    Component(
      ProposalId.Eip(3529),
      rules =>
        rules.copy(
          evm = rules.evm.copy(
            schedule = rules.evm.schedule.copy(
              refundNetStorageClear = ClearingRefund,
              refundSelfDestruct = BigInt(0)
            )
          ),
          execution = rules.execution.copy(maxRefundQuotient = MaxRefundQuotient)
        )
    )
