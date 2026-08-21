package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, GasForwarding, Opcode, Operation, Proposal}

/** EIP-150 -- the IO-heavy repricing, in three deltas.
  *
  * ==Why every file in this tree is named for a PROPOSAL and never for a fork==
  *
  * This document is the measured case, which is why the reasoning is recorded
  * here rather than in a file with nothing to show for it. EIP-150 is one
  * proposal with three activation points across the networks this project
  * targets. `ethereum/go-ethereum` at `6bb0588ad` activates it on its mainnet
  * at block 2,463,000 (`params/config.go`); `ethereumclassic/core-geth` at
  * `4185df450` activates the same proposal on Ethereum Classic mainnet at
  * 2,500,000 and on Mordor at zero, that network having launched with it
  * already in force (`params/config_classic.go`, `params/config_mordor.go`).
  * `besu-eth/besu-etc` at `eb4248c99` gives the two families separate
  * milestones for it outright.
  *
  * And the fork each family runs NEXT carries a different set: `core-geth` puts
  * `EIP160FBlock` at 3,000,000, where `go-ethereum` at `6bb0588ad` folds the
  * same proposal into `EIP158Block` at 2,675,000 -- so the two group proposals
  * into forks differently even where they run the same ones.
  *
  * **So a fork is composed per network, and a proposal that carried a fork's
  * name could not be composed at all.** A network's own word for the upgrade
  * belongs to `UpgradeId`; the document that supplies the rule belongs here.
  */
object Eip150:

  /** The operations that read account state cost more.
    *
    * Four prices move and nothing else does. What the four have in common is a
    * state read rather than a computation, which is the proposal's own
    * reasoning: loading a storage slot, reading an account's balance, reading
    * another account's code, and reaching another account at all were priced
    * against arithmetic rather than against the disk they touch.
    *
    * ==A settled price has two homes, and moving one of them is silent==
    *
    * `OpcodeTable.original` copies a settled price out of the schedule when it
    * builds an entry, so an operation charged through that entry is charged what
    * the table was built with rather than what the schedule holds now. A
    * proposal that moved the schedule alone would leave `BALANCE`, `EXTCODESIZE`
    * and `SLOAD` charging exactly what they charged before, with nothing in the
    * schedule to show it. So this names the table too, which is what
    * `OpcodeTable.adding` is for: an entry inserted over an operation already
    * present replaces it.
    *
    * `EXTCODECOPY` and the call family read their settled part from the schedule
    * at the moment they spend it and need no entry. `GasSchedule.externalBase`
    * is therefore read from both places at once -- the table for `EXTCODESIZE`,
    * the schedule for `EXTCODECOPY` -- which is why one field moving has to
    * reach both.
    *
    * **This is an instance of a general property, not a fact about these four
    * fields.** `GasSchedule` states the classes a price falls into and how to
    * re-derive which is which. A proposal moving a price this one does not name
    * reads that first: some fields need no table work at all, and some are
    * reached ONLY through the table or the precompile set, where editing the
    * schedule alone does nothing whatever.
    */
  val stateReadRepricing: Proposal =
    rules =>
      val repriced = rules.schedule.copy(
        storageLoad = BigInt(200),
        balance = BigInt(400),
        externalBase = BigInt(700),
        callBase = BigInt(700)
      )
      rules.copy(
        schedule = repriced,
        table = rules.table
          .adding(Operation(Opcode.SLoad, Cost.Fixed(repriced.storageLoad)))
          .adding(Operation(Opcode.Balance, Cost.Fixed(repriced.balance)))
          .adding(Operation(Opcode.ExtCodeSize, Cost.Fixed(repriced.externalBase)))
      )

  /** A nested invocation is given all but one sixty-fourth of what the caller
    * has left.
    *
    * The proposal's own reason is not the gas: it is that a caller asking for
    * more than it holds used to be a frame that ran out of gas, and contracts
    * computing their request from what they held were written against prices
    * this same proposal moves. Capping rather than refusing keeps those callers
    * working, and the sixty-fourth left behind is what the caller then has to
    * act on whatever came back.
    *
    * ==One rule serves `CREATE` as well, and that is arithmetic rather than
    * economy==
    *
    * A creation names no request; it forwards everything it holds. So its
    * request and what remains are the same number, and capping either is the
    * same operation -- which is why this reaches four operations through one
    * member rather than needing a second for the one that asks for nothing.
    *
    * ==It REPLACES the rule it finds, and now it has to say so==
    *
    * This assigns a case rather than composing over whatever was there. When
    * the member was a function that distinction was invisible: a body ignoring
    * its predecessor and a body calling it looked alike at the call site, so a
    * later proposal could discard this cap by accident. As data it cannot -- a
    * proposal that wants to refine rather than replace has no way to express
    * itself without adding a case to `GasForwarding`, which is a deliberate
    * act. [[org.fukuii.evm.GasForwarding]] records that reasoning in full.
    */
  val forwardedGasCap: Proposal = _.copy(gasForwarded = GasForwarding.AllButOneSixtyFourth)

  /** Ending an invocation and giving its balance away is charged for, and
    * charged more where the account paid out to has never existed.
    *
    * A repricing in place, and only because the original schedule was built to
    * let it be one: both fields are already there priced at nothing, and the
    * operation already works out its own charge rather than carrying a settled
    * one. A schedule that named neither would make this a change to the record's
    * shape and to how the operation is dispatched at the same time.
    *
    * The surcharge is the same figure the call family pays for the same
    * situation and is a different field on purpose --
    * `GasSchedule.selfDestructNewAccount` records why.
    */
  val selfDestructCharge: Proposal =
    rules =>
      rules.copy(schedule = rules.schedule.copy(selfDestruct = BigInt(5000), selfDestructNewAccount = BigInt(25000)))

  /** Adopting the document, which is adopting all three of its deltas. */
  val component: Component =
    Component.evm(ProposalId.Eip(150), stateReadRepricing, forwardedGasCap, selfDestructCharge)
