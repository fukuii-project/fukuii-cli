package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal, StateAccessMetering}

/** EIP-2929 -- reaching state costs more the first time and less afterwards.
  *
  * ==Three new prices, five moved ones, four rebuilt entries, and one rule==
  *
  * The three the document names in its own Parameters table (`ethereum/EIPs` @
  * `dbfa6bee8`, `EIPS/eip-2929.md`, Final) are `COLD_SLOAD_COST` 2100,
  * `COLD_ACCOUNT_ACCESS_COST` 2600 and `WARM_STORAGE_READ_COST` 100.
  * `ethereum/execution-specs` @ `20f7f6271` states the same three at
  * `forks/berlin/vm/gas.py:40-42` as `WARM_ACCESS`, `COLD_ACCOUNT_ACCESS` and
  * `COLD_STORAGE_ACCESS`, and `besu-eth/besu` @ `fdf1247c6` a third time at
  * `BerlinGasCalculator.java:38-42`.
  *
  * The rule is `org.fukuii.evm.StateAccessMetering`, whose own scaladoc carries
  * which of the eleven affected operations reads it how -- including the two the
  * document makes exceptions of.
  *
  * ==THE FIVE MOVED FIGURES ARE THE COMPOSITE-DEFINITION HAZARD THE DOCUMENT
  * WARNS ABOUT, AND THIS SCHEDULE IS EXACTLY THE CASE IT WARNS==
  *
  * *"The constant `SLOAD_GAS` is used in several places in EIP 2200, e.g
  * `SSTORE_SET_GAS - SLOAD_GAS`. Implementations that are using composite
  * definitions have to ensure to update those definitions too."*
  * [[Eip2200]] holds four such definitions as settled literals and its own
  * scaladoc records that they were derived rather than read. Every one of those
  * derivations re-runs here:
  *
  *   - `SLOAD_GAS` becomes `WARM_STORAGE_READ_COST`, so
  *     `org.fukuii.evm.GasSchedule.netStorageNoop` and `netStorageDirty` go from
  *     800 to 100;
  *   - `SSTORE_RESET_GAS` becomes `5000 - COLD_SLOAD_COST`, so `netStorageClean`
  *     goes from 5000 to 2900;
  *   - `SSTORE_SET_GAS - SLOAD_GAS` is then `20000 - 100`, so
  *     `refundNetStorageResetFromZero` goes from 19,200 to 19,900 -- it RISES,
  *     which is the one of the five a reader scanning for reductions will not
  *     expect;
  *   - `SSTORE_RESET_GAS - SLOAD_GAS` is then `2900 - 100`, so
  *     `refundNetStorageReset` goes from 4,200 to 2,800.
  *
  * `ethereum/execution-specs` @ `20f7f6271` writes each as an expression rather
  * than a literal at `forks/berlin/vm/instructions/storage.py:80-118`, which is
  * how the four were checked against something that does not restate them.
  *
  * ==Which figures do NOT move, each for a stated reason==
  *
  * `netStorageInit` stays 20,000: it is `SSTORE_SET_GAS`, which the document's
  * table leaves alone. `refundNetStorageClear` stays where it is -- EIP-3529
  * moves that one, at a later upgrade.
  *
  * **`storageLoad` and `storageReset` are NOT moved, and that is a reading of
  * the document rather than an omission.** Its table modifies *"the parameters
  * defined in EIP-2200"*, and in this schedule those are the five above.
  * `storageLoad` is the `SLOAD` OPERATION's settled price, which this document
  * does not reprice but REPLACES -- that operation is priced warm-or-cold at
  * spend time from here on, so the field is read by nothing. `storageReset`
  * belongs to the legacy metering scheme, which `org.fukuii.evm.StorageMetering`
  * made unreachable two documents ago. Writing a figure into either would be
  * stating a price no source states and nothing spends.
  *
  * ==Four entries stop holding a figure==
  *
  * `BALANCE`, `EXTCODESIZE`, `EXTCODEHASH` and `SLOAD` carried
  * `org.fukuii.evm.Cost.Fixed` and now work out their own charge, so their
  * entries are rebuilt to `org.fukuii.evm.Cost.Computed`. The other seven
  * operations this document reaches -- `EXTCODECOPY`, the four call forms,
  * `SELFDESTRUCT` and `SSTORE` -- already carry that constructor and need no
  * entry change, which is not the same as needing no change: each reads the rule
  * at the moment it spends.
  *
  * That is the eleven `ethereum/go-ethereum` @ `e9e35a42f` rewrites in
  * `enable2929` (`core/vm/eips.go:124-160`).
  *
  * ==The coinbase is not warmed by this document==
  *
  * A reader taking go-ethereum's `StateDB.Prepare` at face value would seed it:
  * that function takes the block's beneficiary in its signature. It warms it
  * under `if rules.IsShanghai`, for EIP-3651, and the Berlin members sit
  * unguarded above. `org.fukuii.execution.TransactionProcessor` carries the seed
  * and states the same boundary at the site.
  */
object Eip2929:

  /** The three new figures arrive and the five composite ones re-derive, in one
    * delta because they are one document's arithmetic.
    *
    * Written as one because splitting them would let a network take the new
    * constants without re-running the derivations that read them, which is the
    * schedule the document's own warning describes: internally inconsistent,
    * compiling, and wrong only when a store executes.
    */
  val accessRepricing: Proposal =
    rules =>
      rules.copy(schedule =
        rules.schedule.copy(
          warmAccess = BigInt(100),
          coldAccountAccess = BigInt(2600),
          coldStorageAccess = BigInt(2100),
          netStorageNoop = BigInt(100),
          netStorageDirty = BigInt(100),
          netStorageClean = BigInt(2900),
          refundNetStorageResetFromZero = BigInt(19900),
          refundNetStorageReset = BigInt(2800)
        )
      )

  /** The scheme the eleven affected operations read when they spend.
    *
    * Separate from the repricing because it settles a rule and not a figure, and
    * because a network could state the figures without the scheme -- which would
    * be a schedule carrying three prices nothing reads.
    */
  val warmAndColdAccess: Proposal = _.copy(stateAccessMetering = StateAccessMetering.WarmCold)

  /** The four entries that stop being settled before their operation runs.
    *
    * Their prices are not moved to the new figures and then rebuilt, which is
    * the shape [[Eip1884]] takes: there is no figure to move to, the charge
    * being decided at spend time by what the transaction has already reached. So
    * the entries lose their number rather than getting a new one.
    */
  val computedAtSpendTime: Proposal =
    rules =>
      rules.copy(table =
        rules.table
          .adding(Operation(Opcode.Balance, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeSize, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeHash, Cost.Computed))
          .adding(Operation(Opcode.SLoad, Cost.Computed))
      )

  /** Adopting the document, which is adopting all three of its deltas. */
  val component: Component =
    Component.evm(ProposalId.Eip(2929), accessRepricing, warmAndColdAccess, computedAtSpendTime)
