package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, GasSchedule, Opcode, OpcodeTable, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1884 changes, and the far larger set it must leave alone.
  *
  * ==Two of the three prices are read only where the table is BUILT==
  *
  * So a delta moving the record and stopping would leave those operations
  * charging what they charged at the fork below with a record saying otherwise,
  * and nothing anywhere reading the difference. Every schedule case below is
  * paired with one over the entry built from it, for the reason
  * `org.fukuii.evm.GasSchedule` states and [[Eip150Spec]] first met.
  *
  * ==`SLOAD_GAS` is one constant in the document and THREE fields here, and
  * this document owns exactly one of them==
  *
  * The 800 below is the price of the `SLOAD` OPERATION. The same figure also
  * appears inside the `SSTORE` calculation, where it is two different members
  * that [[Eip2200]] moves from its own Specification. Both documents land at
  * this upgrade and both say *"`SLOAD_GAS` becomes 800"*; moving the wrong set
  * produces a schedule that compiles and charges a storage read one price
  * through one operation and another through the other. [[Eip2200Spec]] asserts
  * the mirror of the case below.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The three figures, the byte and the tier are read from the proposal's own
  * Specification. What the new operation PUSHES is the machine's, and
  * `org.fukuii.evm`'s `SelfBalanceSpec` certifies it there.
  */
class Eip1884Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.petersburg

  private val adopted: UpgradeRules = base.adopting(Eip1884.component)

  private val before: GasSchedule = base.evm.schedule

  private val after: GasSchedule = adopted.evm.schedule

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "adopting EIP-1884" should "move all three prices the document names" in
    // "The `SLOAD` (`0x54`) operation changes from `200` to `800` gas", "The
    // `BALANCE` (`0x31`) operation changes from `400` to `700` gas" and "The
    // `EXTCODEHASH` (`0x3F`) operation changes from `400` to `700` gas",
    // ethereum/EIPs @ dbfa6bee, EIPS/eip-1884.md, Final. Asserted as this
    // network's own literals: every figure these move from is shared with the
    // other network this repository configures, so a comparison across the two
    // would go on agreeing with a mutation that moved both.
    assert(
      after.storageLoad == BigInt(800) && after.balance == BigInt(700) && after.extCodeHash == BigInt(700),
      "a price the document names did not move, or moved to something else"
    )

  it should "have carried the earlier prices before it was adopted" in
    // The control. EIP-150 left two of these where this document finds them and
    // EIP-1052 introduced the third at 400 and never moved it.
    assert(
      before.storageLoad == BigInt(200) && before.balance == BigInt(400) && before.extCodeHash == BigInt(400),
      "a price this document raises was already at its raised value"
    )

  it should "reprice the table entry of every one of the three" in
    // THE case this half of the spec exists for. Two of these three are read
    // only when the table is built, so a delta editing the record alone leaves
    // them charging the fork below's figures with nothing to report it.
    assert(
      settledCost(adopted.evm.table, Opcode.SLoad).contains(BigInt(800)) &&
        settledCost(adopted.evm.table, Opcode.Balance).contains(BigInt(700)) &&
        settledCost(adopted.evm.table, Opcode.ExtCodeHash).contains(BigInt(700)),
      "an operation charged through the table kept its earlier price"
    )

  it should "carry the EXTCODEHASH half its own Rationale contradicts" in
    // The named negative, stated separately so a failure says which mistake was
    // made. Arguing for the BALANCE increase the document calls EXTCODEHASH
    // "priced at `700` already" -- it was not, it stood at 400 going into this
    // fork, and reading the Rationale as normative would leave this operation
    // unrepriced while every other case in this file passed.
    assert(
      !settledCost(adopted.evm.table, Opcode.ExtCodeHash).contains(BigInt(400)),
      "EXTCODEHASH was left at the price the document's Rationale wrongly assumes it already had"
    )

  it should "leave no entry charging a price its own record has moved away from" in
    // The pairing above stated as the agreement rather than as literals, so a
    // later repricing that moves the figures and forgets an entry fails here
    // even where both literal cases were updated to match it.
    assert(
      settledCost(adopted.evm.table, Opcode.SLoad).contains(after.storageLoad) &&
        settledCost(adopted.evm.table, Opcode.Balance).contains(after.balance) &&
        settledCost(adopted.evm.table, Opcode.ExtCodeHash).contains(after.extCodeHash),
      "an entry and the record it is priced from disagree"
    )

  it should "not already run SELFBALANCE in the table it adds to" in
    assert(base.evm.table.operationAt(0x47).isEmpty, "the preceding table already ran an operation at this byte")

  it should "place SELFBALANCE at 0x47" in
    // "A new opcode, `SELFBALANCE` is introduced at `0x47`", EIPS/eip-1884.md.
    assert(
      adopted.evm.table.operationAt(0x47).map(_.opcode).contains(Opcode.SelfBalance),
      "the document names 0x47"
    )

  it should "price SELFBALANCE from the low tier" in
    // "`SELFBALANCE` is priced as `GasFastStep`, at `5` gas". GasFastStep is
    // the low tier rather than a figure of this operation's own, so the entry
    // names the tier and moves when a network reprices it.
    assert(
      settledCost(adopted.evm.table, Opcode.SelfBalance).contains(base.evm.schedule.low),
      "SELFBALANCE must be charged the tier its document names"
    )

  it should "charge SELFBALANCE exactly five on this network" in
    // The tier's own value as this network's literal. The case above reads the
    // figure out of the same record the delta reads it from, so the two agree
    // whatever that record holds; only a literal says what a node charges.
    assert(
      settledCost(adopted.evm.table, Opcode.SelfBalance).contains(BigInt(5)),
      "this network states its low tier at 5, so SELFBALANCE costs 5 here"
    )

  it should "follow the low tier onto a network that prices it differently" in {
    // The case that separates the tier from a literal of the same value. The
    // repricing half of this component does not touch the tier, so a delta
    // naming the tier answers 11 and one naming 5 answers 5.
    val elsewhere = base.copy(evm = base.evm.copy(schedule = base.evm.schedule.copy(low = 11)))
    assert(
      settledCost(elsewhere.adopting(Eip1884.component).evm.table, Opcode.SelfBalance).contains(BigInt(11)),
      "a network repricing the low tier did not reprice the operation the document puts on it"
    )
  }

  it should "leave EXTCODESIZE and EXTCODECOPY where it found them" in
    // The document brings two operations UP to the figure a third already
    // carried rather than moving the family, so the tier those two are priced
    // from must not move with them.
    assert(
      after.externalBase == before.externalBase && after.externalBase == BigInt(700) &&
        settledCost(adopted.evm.table, Opcode.ExtCodeSize) == settledCost(base.evm.table, Opcode.ExtCodeSize),
      "repricing the trie-size operations moved the tier the external-code operations share"
    )

  it should "leave the storage-write metering figures to the other document that names them" in
    // The mirror of EIP-2200's case. This document's SLOAD_GAS is the
    // operation's price alone; the two members carrying the same quantity
    // inside the SSTORE calculation are the other document's, and a delta
    // moving both would take a decision that is not this one's to take.
    assert(
      after.netStorageNoop == before.netStorageNoop && after.netStorageDirty == before.netStorageDirty,
      "this document moved a storage-metering figure that belongs to EIP-2200"
    )

  it should "leave every entry it does not name exactly as it found it" in {
    // Stated as the set that moved rather than as spot checks, so an entry
    // reached by accident fails as loudly as one missed on purpose. The new
    // operation is in the set because an absent entry becoming a present one is
    // a move like any other.
    val named = Set(Opcode.SLoad, Opcode.Balance, Opcode.ExtCodeHash, Opcode.SelfBalance)
    val moved =
      Opcode.values.toSet.filter(opcode =>
        adopted.evm.table.operationAt(opcode.code) != base.evm.table.operationAt(opcode.code)
      )
    assert(moved == named, s"the entries that moved were ${moved.toString} rather than ${named.toString}")
  }

  it should "add exactly one entry, having repriced three" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "move no figure the document does not name" in
    // Stated as the whole record rather than as spot checks, so a price reached
    // by accident fails as loudly as one of the three failing to move.
    assert(
      after == before.copy(storageLoad = BigInt(800), balance = BigInt(700), extCodeHash = BigInt(700)),
      "the repriced record differs from the earlier one by something other than the three figures"
    )

  it should "leave the precompile set as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "a repricing of operations rebuilt the natives"
    )

  it should "settle those entries and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(
        schedule = after,
        table = base.evm.table
          .adding(Operation(Opcode.SLoad, Cost.Fixed(BigInt(800))))
          .adding(Operation(Opcode.Balance, Cost.Fixed(BigInt(700))))
          .adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(BigInt(700))))
          .adding(Operation(Opcode.SelfBalance, Cost.Fixed(base.evm.schedule.low)))
      ),
      "the adopting rules differ from the earlier ones by something other than the record and the four entries"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a repricing inside the machine altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(1884)), "the journal must record what was adopted")
