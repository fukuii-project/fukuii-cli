package org.fukuii.consensus.pos

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

import org.fukuii.bytes.UInt64
import org.fukuii.types.Withdrawal

/** Whether the identifier actually depends on the parameters it claims to.
  *
  * ==A derivation that ignored a field would pass every obvious test==
  *
  * An identifier is eight opaque bytes and nothing reads them, so there is no
  * value to compare against and no fixture that could disagree. A derivation
  * that hashed only the parent and the timestamp would still be deterministic,
  * still be unique-looking, and still satisfy every assertion about the same
  * request answering the same identifier.
  *
  * **So the evidence has to be differential.** Each row below alters exactly one
  * parameter and requires the identifier to move; a derivation that dropped that
  * parameter fails that row and nothing else.
  *
  * The negative control is the last property: a field the derivation
  * deliberately does not cover must NOT move it. Without that, a table of
  * "everything changes it" would also pass for a derivation that hashed random
  * bytes, and the rows would be measuring nothing.
  */
class PayloadBuildRequestPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val head = PosFixtures.hash(0xaa)
  private val base = PayloadBuildRequest(head, PosFixtures.attributesWithBeaconRoot)

  private val otherWithdrawal =
    Withdrawal(UInt64.fromBits(9L), UInt64.fromBits(9L), PosFixtures.address(0x09), UInt64.fromBits(9L))

  private val altered = Table(
    ("which parameter is altered", "request"),
    ("the head it builds on", base.copy(head = PosFixtures.hash(0xbb))),
    (
      "the timestamp",
      base.copy(attributes = base.attributes.copy(timestamp = UInt64.fromBits(1234567890L)))
    ),
    (
      "the randomness",
      base.copy(attributes = base.attributes.copy(prevRandao = PosFixtures.hash(0xcc)))
    ),
    (
      "the suggested fee recipient",
      base.copy(attributes = base.attributes.copy(suggestedFeeRecipient = PosFixtures.address(0xdd)))
    ),
    (
      "the withdrawal list's contents",
      base.copy(attributes =
        base.attributes.copy(appended =
          Some(AttributesWithdrawals(Seq(otherWithdrawal), Some(AttributesBeaconRoot(PosFixtures.hash(0x99)))))
        )
      )
    ),
    (
      "the parent beacon block root",
      base.copy(attributes =
        base.attributes.copy(appended =
          Some(AttributesWithdrawals(Seq(PosFixtures.withdrawal), Some(AttributesBeaconRoot(PosFixtures.hash(0xee)))))
        )
      )
    ),
    ("the structure version, by dropping the appended chain", base.copy(attributes = PosFixtures.bareAttributes))
  )

  property("the same request answers the same identifier") {
    assert(
      base.payloadId == PayloadBuildRequest(head, PosFixtures.attributesWithBeaconRoot).payloadId,
      "a consensus layer repeating a request must not start a second build, which is why this is derived"
    )
  }

  property("altering any covered parameter moves the identifier") {
    forAll(altered) { (what: String, request: PayloadBuildRequest) =>
      assert(
        request.payloadId != base.payloadId,
        what + " left the identifier unchanged, so the derivation does not cover it"
      )
    }
  }

  property("every altered request is still distinct from every other") {
    val ids = altered.map(_._2.payloadId).toSeq :+ base.payloadId
    assert(
      ids.distinct.size == ids.size,
      "two different builds sharing an identifier would have getPayload answer the wrong block"
    )
  }

  property("the identifier is eight bytes") {
    assert(
      base.payloadId.toBytes.length == PayloadId.Width,
      "the specification fixes the width, so a derivation answering more or fewer is not one"
    )
  }

  /** ==A tripwire pointed at the hazard rather than at the fix==
    *
    * These two asserted the opposite until the derivation folded a family's
    * fields in, and the shape of that mistake is worth recording: a test
    * pinning a known gap fires on the commit that CLOSES the gap and never on
    * the one that makes it dangerous. Its failure message then argues the
    * reader out of the fix, which is worse than having no test at all.
    *
    * What makes them dangerous is a family appending build parameters, and
    * that is what these two now catch — one that a contribution reaches the
    * identifier, one that two different contributions do not collapse onto the
    * same one.
    */
  property("a family's own appended attributes move the identifier") {
    val withFamilyFields =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(1))))
    assert(
      withFamilyFields.payloadId != base.payloadId,
      "a build parameter outside the preimage is one two builds can differ in while sharing an identifier, and " +
        "the second would be served the first one's payload"
    )
  }

  property("two family values a network would treat as different attributes get different identifiers") {
    val one =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(1))))
    val two =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(2))))
    assert(
      one.payloadId != two.payloadId,
      "folding a family's contribution in buys nothing if every family value contributes the same bytes"
    )
  }
