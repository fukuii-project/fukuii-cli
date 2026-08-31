package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Proposal, StorageMetering}

/** EIP-2200 -- net gas metering, restated with a sentry and a repricing.
  *
  * ==The case carries the RULE and the schedule carries the FIGURES==
  *
  * The document is a combination rather than a new scheme: it restates
  * EIP-1283's nine clauses unchanged in structure, adds one refusal from
  * EIP-1706, and moves one variable. *"Change the definition of EIP-1283 using
  * those variables. The new specification, combining EIP-1283 and EIP-1706, will
  * look like below."* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-2200.md`, Final).
  *
  * So the two deltas below are the two halves. The refusal is a rule and cannot
  * be a number, which is what `org.fukuii.evm.StorageMetering.NetWithSentry`
  * is; that case's own scaladoc carries the quoted condition, why the threshold
  * is `org.fukuii.evm.GasSchedule.callStipend` rather than a figure of its own,
  * and how a network reaches the same rule without ever naming this document.
  * The rest is prices.
  *
  * ==`SLOAD_GAS` is ONE variable here and THREE fields in this schedule==
  *
  * *"`SLOAD_GAS`: changed from `200` to `800`."* -- the only variable of the
  * four that moves, the other three being carried forward at their existing
  * values by the document's own list. It appears in two of the nine clauses:
  * *"If *current value* equals *new value* (this is a no-op), `SLOAD_GAS` is
  * deducted"* and *"If *original value* does not equal *current value* (this
  * storage slot is dirty), `SLOAD_GAS` gas is deducted"*. Those are
  * `org.fukuii.evm.GasSchedule.netStorageNoop` and
  * `org.fukuii.evm.GasSchedule.netStorageDirty`.
  *
  * **The third field the same figure reaches is the `SLOAD` OPERATION's price,
  * and it is NOT this document's.** That one is
  * `org.fukuii.evm.GasSchedule.storageLoad` and belongs to [[Eip1884]], which
  * moves it to the same 800 from its own Specification. Both documents land
  * together, both cite one constant -- `ethereum/execution-specs` @
  * `20f7f6271a`, `forks/istanbul/vm/gas.py:40`, `SLOAD: Final[Uint] = Uint(800)`
  * -- and this schedule splits it three ways because a network can move the
  * operation without moving the metering. Adopting one document and not the
  * other leaves those prices disagreeing, which nothing checks.
  *
  * ==The two refunds are DERIVED, and the document never states them as
  * numbers==
  *
  * *"If *original value* is 0, add `SSTORE_SET_GAS - SLOAD_GAS` to refund
  * counter. Otherwise, add `SSTORE_RESET_GAS - SLOAD_GAS` gas to refund
  * counter."* Both are differences, and `SLOAD_GAS` is the term that moved:
  * `20000 - 800` is 19,200 and `5000 - 800` is 4,200, where EIP-1283's same two
  * expressions gave 19,800 and 4,800 against the old 200. So these two figures
  * move as a CONSEQUENCE of the one variable this document changes, and a reader
  * checking them against the text will find expressions rather than the
  * literals. `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/istanbul/vm/instructions/storage.py:97-104` computes them the same way
  * rather than storing them, as `STORAGE_SET - SLOAD` and `COLD_STORAGE_WRITE -
  * SLOAD`.
  *
  * They are held as settled fields here because this schedule holds refunds as
  * prices, which `org.fukuii.evm.GasSchedule` states and which lets a network
  * move one without re-deriving the other two.
  *
  * ==What it does NOT reach==
  *
  * [[Eip1283]] and [[Eip1716]], which keep naming the values they name today.
  * This document restates them rather than amending them, and a network that ran
  * either at some height ran it at the figures in force there.
  *
  * The operation set and the precompile set, and `SSTORE_SET_GAS`,
  * `SSTORE_RESET_GAS` and `SSTORE_CLEARS_SCHEDULE`, all three of which the
  * document lists explicitly as *"not changed"*. Every field below is read at
  * the moment it is spent rather than copied into an entry, so moving the record
  * is the whole of this repricing.
  */
object Eip2200:

  /** The nine clauses, refused outright when the frame has too little gas left
    * to enter them.
    */
  val netMeteringWithSentry: Proposal = _.copy(storageMetering = StorageMetering.NetWithSentry)

  /** `SLOAD_GAS` moves, and the two refunds defined as differences from it move
    * with it.
    */
  val storageRepricing: Proposal =
    rules =>
      rules.copy(schedule =
        rules.schedule.copy(
          netStorageNoop = BigInt(800),
          netStorageDirty = BigInt(800),
          refundNetStorageResetFromZero = BigInt(19200),
          refundNetStorageReset = BigInt(4200)
        )
      )

  /** Adopting the document, which is adopting both of its deltas. */
  val component: Component = Component.evm(ProposalId.Eip(2200), netMeteringWithSentry, storageRepricing)
