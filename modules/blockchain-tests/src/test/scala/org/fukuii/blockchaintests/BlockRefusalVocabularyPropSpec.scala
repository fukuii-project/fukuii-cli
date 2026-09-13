package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.UInt256
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.execution.{BlockRejection, Refusal}

/** Whether a refusal this build produced is one a published name states, as a
  * table of named cases with one expected answer each.
  *
  * ==A name's accepting row has a twin that must refuse==
  *
  * A vocabulary that satisfied every name with every fault would pass any row
  * asking only whether a right refusal is accepted. So each name is held against
  * the fault it names and against a neighboring fault it does not, and the
  * second is what shows the mapping discriminates. The row stating alternatives
  * has no twin of its own.
  *
  * `BlockRefusalVocabularySpec` holds the example of the other member, the
  * names a stated refusal carries that neither half of the vocabulary holds.
  */
class BlockRefusalVocabularyPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def fee(value: Long): UInt256 = UInt256.fromLong(value).fold(error => fail(error.toString), identity)

  private val GasLimit: BlockFault = BlockFault.Header(HeaderFault.GasLimitOutOfBounds(BigInt(4999), BigInt(5000)))

  private val BaseFee: BlockFault = BlockFault.Header(HeaderFault.BaseFeeMismatch(fee(1), fee(7)))

  private def transaction(reason: Refusal): BlockFault =
    BlockFault.TransactionRefused(BlockRejection(index = 0, reason = reason, unbuilt = None))

  /** A key no published corpus states, so it can never be added and stop being
    * the name a vocabulary lacks.
    */
  private val Unpublished: String = "BlockException.NOT_A_PUBLISHED_NAME"

  private val Rows = Table(
    ("case", "stated", "fault", "satisfied"),
    ("a gas limit out of bounds, for the gas-limit name", Set("BlockException.INVALID_GASLIMIT"), GasLimit, true),
    ("a base fee mismatch, for the gas-limit name", Set("BlockException.INVALID_GASLIMIT"), BaseFee, false),
    ("a base fee mismatch, for the base-fee name", Set("BlockException.INVALID_BASEFEE_PER_GAS"), BaseFee, true),
    (
      "a missing base fee, for the base-fee name, which names a miscalculated one",
      Set("BlockException.INVALID_BASEFEE_PER_GAS"),
      BlockFault.Header(HeaderFault.BaseFeeMissing),
      false
    ),
    (
      "a transaction refused for the reason its name maps to",
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.IntrinsicGasTooLow),
      true
    ),
    (
      "a transaction refused for another reason",
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.InsufficientAccountFunds),
      false
    ),
    ("a header fault, for a transaction name", Set("TransactionException.INTRINSIC_GAS_TOO_LOW"), GasLimit, false),
    (
      "a refusal under one of the alternatives a case states",
      Set("TransactionException.INVALID_SIGNATURE_VRS", "TransactionException.INVALID_CHAINID"),
      transaction(Refusal.WrongChainId),
      true
    ),
    ("a header fault, under a name nothing holds", Set(Unpublished), GasLimit, false),
    (
      "a transaction refusal, under a name nothing holds",
      Set(Unpublished),
      transaction(Refusal.IntrinsicGasTooLow),
      false
    )
  )

  property("a refusal satisfies exactly the stated names that map to its rule") {
    forAll(Rows) { (label, stated, fault, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfies(ExpectedRejection(stated), fault) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + fault.toString
      )
    }
  }
