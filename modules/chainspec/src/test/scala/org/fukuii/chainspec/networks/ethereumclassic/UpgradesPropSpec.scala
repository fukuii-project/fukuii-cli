package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{DifficultyAdjustment, UpgradeRules}
import org.fukuii.evm.{Cost, NewAccountCharge, Opcode, Operation, Precompile, PrecompileSet, StorageMetering}
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** What each proposal of a multi-proposal upgrade settles, and that none of it
  * was settled before. Facts about a composition as a whole live in
  * [[UpgradesSpec]].
  *
  * ==One table read in both directions, because the second direction is the
  * control==
  *
  * A component adopted by no network still passes its own spec, so asserting a
  * value at the upgrade that adopts it is what makes the adoption observable.
  * That claim alone holds for a network that carried the value all along, which
  * is what the second property is for: the same predicates read at the rule set
  * below, where every one of them must be false.
  *
  * ==Rows are observables rather than proposals==
  *
  * EIP-161 settles four things and EIP-211 two, and a row per proposal would
  * have to conjoin them -- which loses the control, because a conjunction is
  * already false at the upgrade below when any one clause is. A row per
  * observable keeps each one refutable in both directions, and the proposal is
  * named in the row so a failure says which document is unaccounted for.
  */
class UpgradesPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** What a rule set's table charges for `opcode` before it runs, where that is
    * settled.
    *
    * Read off the entry rather than off the record it was built from, because a
    * fixed-price entry is settled at the moment the operation is adopted: a
    * repricing that moved the schedule and left the entry alone would leave the
    * two disagreeing, and only the entry is what a frame is billed.
    */
  private def settledCost(rules: UpgradeRules, opcode: Opcode): Option[BigInt] =
    rules.evm.table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  /** Every observable the ten proposals of [[Upgrades.atlantis]] settle,
    * keyed to the document that settles it.
    *
    * The prices these place are not read here. What a native costs and what an
    * operation charges are settled by comparison against the network this one
    * shares the machine with, in
    * [[org.fukuii.chainspec.networks.SharedHistorySpec]], which compares the
    * whole of the machine's rules rather than a member at a time.
    *
    * **That is this upgrade's reason and it does not reach every upgrade.** A
    * comparison is refutable only where the two networks disagree, so it reports
    * an agreement and never an adoption. That is sufficient here, where every
    * proposal adopted settles something other than a price, and it is not
    * sufficient where a proposal settles nothing else. [[phoenixObservables]] is
    * the upgrade that reaches the limit, and it states the exception in full.
    */
  private val observables = Table(
    ("proposal, and what it settles", "in force"),
    (
      "EIP-161 (a): a created account starts at a count of one",
      (rules: UpgradeRules) => rules.evm.createdAccountNonce == UInt64.fromBits(1L)
    ),
    (
      "EIP-161 (b): the surcharge falls only where value reaches a dead destination",
      (rules: UpgradeRules) => rules.evm.newAccountCharge == NewAccountCharge.WhenValueReachesADeadDestination
    ),
    (
      "EIP-161 (d): a touch at the fourth native survives the invocation failing",
      (rules: UpgradeRules) => rules.evm.touchSurvivesFailure == Set(PrecompileSet.Ripemd160)
    ),
    (
      "EIP-161 (d): an account left holding nothing ceases to exist at settlement",
      (rules: UpgradeRules) => rules.execution.touchedEmptyAccountsAreDeleted
    ),
    ("EIP-170: deployed code is bounded", (rules: UpgradeRules) => rules.evm.maxCodeSize.contains(24576)),
    (
      "EIP-100: difficulty targets the ommer-inclusive formula",
      (rules: UpgradeRules) => rules.consensus.difficultyAdjustment == DifficultyAdjustment.Eip100
    ),
    ("EIP-140: the machine can revert", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.Revert)),
    ("EIP-211: return data has a size", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.ReturnDataSize)),
    ("EIP-211: return data can be copied", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.ReturnDataCopy)),
    ("EIP-214: a call can be made static", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.StaticCall)),
    (
      "EIP-658: a receipt states its transaction's outcome",
      (rules: UpgradeRules) => rules.execution.receiptCarriesStatus
    ),
    (
      "EIP-198: a native answers at the fifth address",
      (rules: UpgradeRules) => rules.evm.precompiles.at(PrecompileSet.ModExp).isDefined
    ),
    (
      "EIP-196: a native answers at the sixth address",
      (rules: UpgradeRules) => rules.evm.precompiles.at(PrecompileSet.AltBn128Add).isDefined
    ),
    (
      "EIP-196: a native answers at the seventh address",
      (rules: UpgradeRules) => rules.evm.precompiles.at(PrecompileSet.AltBn128Mul).isDefined
    ),
    (
      "EIP-197: a native answers at the eighth address",
      (rules: UpgradeRules) => rules.evm.precompiles.at(PrecompileSet.AltBn128PairingCheck).isDefined
    )
  )

  /** Every observable the three proposals of [[Upgrades.agharta]] settle, keyed
    * to the document that settles it.
    *
    * A second table rather than rows added to the one above, because the two are
    * read against different upgrades and different controls. Folding them into
    * one would make each row's control the wrong upgrade for half the rows.
    *
    * All five are table entries, so each row is a byte rather than a field. What
    * the two priced ones COST is asserted in [[UpgradesSpec]] against this
    * network's own schedule; presence and price are separate claims, and a row
    * here would report the first while saying nothing about the second.
    */
  private val aghartaObservables = Table(
    ("proposal, and what it settles", "in force"),
    ("EIP-145: the machine can shift left", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.Shl)),
    ("EIP-145: the machine can shift right", (rules: UpgradeRules) => rules.evm.table.contains(Opcode.Shr)),
    (
      "EIP-145: the machine can shift right keeping the sign",
      (rules: UpgradeRules) => rules.evm.table.contains(Opcode.Sar)
    ),
    (
      "EIP-1014: a contract can be created at a derived address",
      (rules: UpgradeRules) => rules.evm.table.contains(Opcode.Create2)
    ),
    (
      "EIP-1052: another account's code has a hash",
      (rules: UpgradeRules) => rules.evm.table.contains(Opcode.ExtCodeHash)
    )
  )

  /** Every observable the six proposals of [[Upgrades.phoenix]] settle, keyed to
    * the document that settles it.
    *
    * ==This table reads PRICES, where the two above it deliberately do not==
    *
    * [[observables]] defers what a native costs and what an operation charges to
    * the comparison against the network this one shares the machine with. Two
    * properties of these six put them outside that reason, and both are facts
    * about the proposals rather than a change of mind about the convention.
    *
    * **Two of the six settle nothing else.** EIP-1108 reprices three natives
    * already placed, and EIP-2028 moves one intrinsic charge; neither adds an
    * operation, an address or a rule. A table declining to read prices would
    * carry four of the six and read as complete.
    *
    * **And a comparison states an agreement rather than an adoption.** It is
    * refutable only where the two networks disagree, and they disagree about no
    * figure these six move: the machines are equal at the upgrade below this one
    * on each network, which [[org.fukuii.chainspec.networks.SharedHistorySpec]]
    * asserts. A figure moving on both sides at once satisfies it, so it cannot
    * state that THIS network adopted a repricing -- which is what a row here is
    * for, and what the second direction refutes.
    *
    * ==An addition priced from a figure this upgrade does not move is a PRESENCE
    * row==
    *
    * The ninth native, `CHAINID` and `SELFBALANCE` are each built from a figure
    * the base already carries, and no delta of these six writes any of the three.
    * So a row stating one of those prices would be false below only because the
    * entry is absent: the presence row with a clause added, and a conjunction is
    * what this file's division of rows exists to avoid. What each of the three
    * costs is asserted in [[UpgradesSpec]], read off the entry.
    *
    * **`SELFBALANCE` has a second reason not to be priced here**, which
    * [[org.fukuii.chainspec.proposals.eip.Eip1884]] carries with its refs: the
    * corroborating specification declares two constants of equal value, and the
    * operation is charged the one that document names. A row citing the other
    * would reach the right number by the wrong route and would go on agreeing if
    * only one of them ever moved.
    */
  private val phoenixObservables = Table(
    ("proposal, and what it settles", "in force"),
    (
      "EIP-152: a native answers at the ninth address",
      (rules: UpgradeRules) => rules.evm.precompiles.at(PrecompileSet.Blake2f).isDefined
    ),
    (
      "EIP-1108: the sixth native is repriced",
      (rules: UpgradeRules) =>
        rules.evm.precompiles.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(BigInt(150)))
    ),
    (
      "EIP-1108: the seventh native is repriced",
      (rules: UpgradeRules) =>
        rules.evm.precompiles.at(PrecompileSet.AltBn128Mul).contains(Precompile.AltBn128Mul(BigInt(6000)))
    ),
    (
      "EIP-1108: what a pairing check costs before any pair is read is repriced",
      (rules: UpgradeRules) =>
        rules.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .collect { case Precompile.AltBn128PairingCheck(base, _) => base }
          .contains(BigInt(45000))
    ),
    (
      "EIP-1108: what each pair adds to a pairing check is repriced",
      (rules: UpgradeRules) =>
        rules.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .collect { case Precompile.AltBn128PairingCheck(_, perPoint) => perPoint }
          .contains(BigInt(34000))
    ),
    (
      "EIP-1344: the machine can push the identifier its own network is known by",
      (rules: UpgradeRules) => rules.evm.table.contains(Opcode.ChainId)
    ),
    (
      "EIP-1884: reading a storage slot is repriced",
      (rules: UpgradeRules) => settledCost(rules, Opcode.SLoad).contains(BigInt(800))
    ),
    (
      "EIP-1884: reading another account's balance is repriced",
      (rules: UpgradeRules) => settledCost(rules, Opcode.Balance).contains(BigInt(700))
    ),
    (
      "EIP-1884: reading another account's code hash is repriced",
      (rules: UpgradeRules) => settledCost(rules, Opcode.ExtCodeHash).contains(BigInt(700))
    ),
    (
      "EIP-1884: the machine can read the balance it is running against",
      (rules: UpgradeRules) => rules.evm.table.contains(Opcode.SelfBalance)
    ),
    (
      "EIP-2028: a non-zero byte of transaction data is repriced",
      (rules: UpgradeRules) => rules.evm.schedule.transactionDataPerNonZeroByte == BigInt(16)
    ),
    (
      "EIP-2200: storage is metered net of what the transaction has already done, behind a sentry",
      (rules: UpgradeRules) => rules.evm.storageMetering == StorageMetering.NetWithSentry
    ),
    (
      "EIP-2200: a write leaving the slot as it found it is repriced",
      (rules: UpgradeRules) => rules.evm.schedule.netStorageNoop == BigInt(800)
    ),
    (
      "EIP-2200: a write to a slot this transaction has already moved is repriced",
      (rules: UpgradeRules) => rules.evm.schedule.netStorageDirty == BigInt(800)
    ),
    (
      "EIP-2200: the refund for resetting a slot that began empty follows the figure that moved",
      (rules: UpgradeRules) => rules.evm.schedule.refundNetStorageResetFromZero == BigInt(19200)
    ),
    (
      "EIP-2200: the refund for resetting a slot that began set follows the figure that moved",
      (rules: UpgradeRules) => rules.evm.schedule.refundNetStorageReset == BigInt(4200)
    )
  )

  property("every proposal the reconvergence adopts settles what its own document settles") {
    forAll(observables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(inForce(Upgrades.atlantis), observable + " -- not in force at the upgrade that adopts it")
    }
  }

  property("none of it was settled at the rule set below that upgrade") {
    forAll(observables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(!inForce(Upgrades.defuse), observable + " -- already in force before the upgrade that adopts it")
    }
  }

  property("every proposal the upgrade above the reconvergence adopts settles what its own document settles") {
    forAll(aghartaObservables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(inForce(Upgrades.agharta), observable + " -- not in force at the upgrade that adopts it")
    }
  }

  property("none of that was settled at the rule set below it either") {
    forAll(aghartaObservables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(!inForce(Upgrades.atlantis), observable + " -- already in force before the upgrade that adopts it")
    }
  }

  property("every proposal the upgrade taken whole from upstream adopts settles what its own document settles") {
    forAll(phoenixObservables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(inForce(Upgrades.phoenix), observable + " -- not in force at the upgrade that adopts it")
    }
  }

  property("none of what that upgrade adopts was settled at the rule set below it") {
    forAll(phoenixObservables) { (observable: String, inForce: UpgradeRules => Boolean) =>
      assert(!inForce(Upgrades.agharta), observable + " -- already in force before the upgrade that adopts it")
    }
  }
