package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.evm.{EvmFixtures, Word}
import org.fukuii.types.TransactionType
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** Which way a transaction's count misses the sender's, as a table of counts
  * against one sender.
  *
  * ==Each direction is its own refusal, and the row between them is the
  * control==
  *
  * [[Refusal.NonceTooLow]] carries why the two are apart. A rule that refused
  * both directions under one reason would satisfy the two refusing rows for
  * one of them only, and a rule that refused nothing would satisfy the
  * admitting row alone -- so the three rows together are what separate the
  * rule from both.
  */
class NonceAdmissionPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val sender: Address = EvmFixtures.address(0x11)

  private val recipient: Address = EvmFixtures.address(0x22)

  /** The count the sender holds, away from zero so that a count below it
    * exists.
    */
  private val held: Long = 7

  /** Rules carrying only the format every network has carried, so no row is
    * decided by a rule it is not about.
    */
  private val rules: AdmissionRules =
    AdmissionRules(
      admittedTypes = Set(TransactionType.Legacy),
      signatureMayCarryChainId = false,
      signatureSMustBeLow = false,
      admitsDelegations = false
    )

  private def world(): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setBalance(sender, Word(BigInt(10).pow(20)))
    built.setNonce(sender, UInt64.fromBits(held))
    built

  private def verdict(nonce: BigInt): Admission =
    TransactionAdmission.admit(
      OfferedTransaction(
        transactionType = TransactionType.Legacy,
        sender = sender,
        nonce = nonce,
        fee = FeeOffer.Fixed(BigInt(1)),
        gasLimit = BigInt(100000),
        to = Some(recipient),
        value = 0,
        data = Bytes.Empty,
        accessList = Seq.empty,
        blobs = None,
        authorizations = None
      ),
      world(),
      BigInt(30000000),
      None,
      None,
      rules,
      EvmFixtures.rules.schedule,
      None
    )

  private def refusal(answer: Admission): Option[Refusal] = answer match
    case Admission.Refused(reason) => Some(reason)
    case Admission.Admitted(_)     => None

  private val Rows = Table(
    ("case", "nonce", "refused"),
    ("a count one below the sender's", BigInt(held - 1), Some(Refusal.NonceTooLow)),
    ("a count far below the sender's", BigInt(0), Some(Refusal.NonceTooLow)),
    ("a count one above the sender's", BigInt(held + 1), Some(Refusal.NonceTooHigh)),
    ("the sender's own count", BigInt(held), None)
  )

  property("a count other than the sender's is refused for the direction it misses in") {
    forAll(Rows) { (label, nonce, refused) =>
      val answer = verdict(nonce)
      assert(refusal(answer) == refused, label + ": " + answer.toString)
    }
  }
