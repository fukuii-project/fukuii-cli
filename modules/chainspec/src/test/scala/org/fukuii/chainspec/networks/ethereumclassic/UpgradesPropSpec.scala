package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{DifficultyAdjustment, UpgradeRules}
import org.fukuii.evm.{NewAccountCharge, Opcode, PrecompileSet}
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

  /** Every observable the ten proposals of [[Upgrades.atlantis]] settle,
    * keyed to the document that settles it.
    *
    * The prices these place are not read here. What a native costs and what an
    * operation charges are settled by comparison against the network this one
    * shares the machine with, in
    * [[org.fukuii.chainspec.networks.SharedHistorySpec]], which compares the
    * whole of the machine's rules rather than a member at a time.
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
