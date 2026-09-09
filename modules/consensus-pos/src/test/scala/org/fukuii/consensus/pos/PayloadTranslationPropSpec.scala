package org.fukuii.consensus.pos

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The translation against block hashes this project did not compute.
  *
  * ==One assertion covers the whole derivation, which is what makes it worth
  * having==
  *
  * A block hash is a digest over every field of the header, so reproducing one
  * a fixture generator produced requires all of it to be right together: the
  * three constants the merge fixed, both derived commitments, the seal's two
  * slots, the tail's depth, and the order every field sits in. There is no
  * partial credit and no way to pass by accident.
  *
  * ==What these vectors do NOT prove==
  *
  * Both carry a randomness of thirty-two zero bytes, so neither would notice a
  * translation that dropped the field. That gap is closed in
  * [[PayloadTranslationSpec]] by an assertion on the header rather than on its
  * hash, which is stated here because a reader counting on the hash to cover
  * everything would be wrong about exactly this field — the one the phase
  * exists to fill.
  */
class PayloadTranslationPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val published = Table(
    ("which published case", "payload"),
    ("a Paris payload with one transaction and no withdrawals", PublishedPayloads.paris),
    ("a Shanghai payload with one transaction and one withdrawal", PublishedPayloads.shanghai)
  )

  property("the header derived from a published payload hashes to the block hash it states") {
    forAll(published) { (what: String, payload: ExecutionPayload) =>
      assert(
        PayloadTranslation.headerOf(NewPayloadRequest(payload)).map(_.hash) == Right(payload.blockHash),
        what + " derived a header hashing to something other than the hash its sender computed"
      )
    }
  }

  property("a published payload passes the checked translation") {
    forAll(published) { (what: String, payload: ExecutionPayload) =>
      assert(
        PayloadTranslation.checkedHeaderOf(NewPayloadRequest(payload)).isRight,
        what + " was refused by the check that compares the derived hash against the stated one"
      )
    }
  }

  property("altering any single payload field breaks the block hash") {
    val mutated = Table(
      ("which field is altered", "payload"),
      ("the parent hash", PublishedPayloads.paris.copy(parentHash = PosFixtures.hash(0x01))),
      ("the fee recipient", PublishedPayloads.paris.copy(feeRecipient = PosFixtures.address(0x02))),
      ("the state root", PublishedPayloads.paris.copy(stateRoot = PosFixtures.hash(0x03))),
      ("the receipts root", PublishedPayloads.paris.copy(receiptsRoot = PosFixtures.hash(0x04))),
      ("the randomness", PublishedPayloads.paris.copy(prevRandao = PosFixtures.hash(0x05))),
      ("the gas used", PublishedPayloads.paris.copy(gasUsed = PublishedPayloads.paris.gasLimit)),
      ("the timestamp", PublishedPayloads.paris.copy(timestamp = PublishedPayloads.paris.gasLimit)),
      ("the extra data", PublishedPayloads.paris.copy(extraData = PublishedPayloads.shanghai.extraData)),
      ("the transaction list", PublishedPayloads.paris.copy(transactions = Seq.empty)),
      ("the withdrawal list", PublishedPayloads.shanghai.copy(appended = None))
    )
    forAll(mutated) { (what: String, payload: ExecutionPayload) =>
      assert(
        PayloadTranslation.checkedHeaderOf(NewPayloadRequest(payload)).isLeft,
        what + " left the block hash unchanged, so the header does not commit to it"
      )
    }
  }
