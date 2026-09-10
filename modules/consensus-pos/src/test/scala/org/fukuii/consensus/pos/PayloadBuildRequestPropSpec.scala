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
  * ==The negative control is determinism, because no field sits outside the
  * preimage any more==
  *
  * This paragraph used to point at a property asserting that a field the
  * derivation deliberately did not cover must NOT move the identifier. **No such
  * field is left**: a family's own appended attributes were the last of them and
  * they are folded in now, so no property here can assert that anything fails to
  * move it, and the description outlived the thing it described.
  *
  * What stops a table of "everything changes it" from also passing for a
  * derivation that hashed random bytes is the property requiring the same
  * request to answer the same identifier twice. Random bytes satisfy every
  * differential row and fail that one.
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

  /** ==The identifier the consensus layer holds, against the one the store is
    * keyed by==
    *
    * The property above asks two equal requests for one identifier, which any
    * function of the parameters satisfies. This asks ONE request twice, and the
    * two stop being the same question the moment a family leaf stops being a
    * function: [[PayloadAttributes.FamilyFields.identityBytes]] documents a leaf
    * handing back a live buffer as something the trait cannot enforce, and such
    * a leaf answers different bytes on a second read.
    *
    * A derivation evaluated per read would then answer one identifier to the
    * consensus layer and key [[PayloadStore]] by another, so `engine_getPayload`
    * refuses a build that is running and the slot is held until it is evicted.
    * **No well-behaved fixture can produce that**, which is why the leaf here
    * misbehaves on purpose: it answers a different byte every time it is asked,
    * which is what a live buffer does.
    *
    * It is local to this property rather than a fixture beside the others,
    * because it carries state and a shared one would leak its count into
    * whichever test ran next.
    */
  property("one request answers one identifier however often it is asked") {
    val drifting = new PayloadAttributes.FamilyFields:
      private var reads = 0
      def identityBytes: IArray[Byte] =
        reads += 1
        IArray(reads.toByte)

    val request = base.copy(attributes = base.attributes.copy(familyFields = Some(drifting)))
    val answeredToTheConsensusLayer = request.payloadId
    val keyedInTheStore = request.payloadId
    assert(
      answeredToTheConsensusLayer == keyedInTheStore,
      "a request that re-derives its identifier per read answers one to the consensus layer and keys the " +
        "store by another, so getPayload refuses a build that is running"
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

  /** ==A leaf that carries nothing is a family, not the absence of one==
    *
    * Folding a family's bytes straight into the preimage made these two the
    * same request: a leaf returning no bytes hashed to what having no family
    * fields hashed to. **A marker case carrying nothing is the implementation
    * the trait's own contract invites**, so the collision sat on the ordinary
    * path rather than a contrived one — [[PayloadStore]] would replace on the
    * shared identifier and `engine_getPayload` would serve the other build's
    * block.
    */
  property("a family leaf carrying nothing is not the same as having no family fields") {
    val silent =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SilentFamilyAttributeFields)))
    assert(
      silent.payloadId != base.payloadId,
      "a family that appends a marker and nothing else must still be told apart from a network that appends nothing"
    )
  }

  /** ==Two families keeping the same promise, colliding anyway==
    *
    * [[PayloadAttributes.FamilyFields.identityBytes]] asks a family to tell its
    * own values apart, which is the most a family can promise: it has never
    * heard of the other one. So two leaves returning the same bytes are two
    * correct implementations, and the identifier space they share is what has
    * to survive them.
    */
  property("two families contributing the same bytes are told apart") {
    val first =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(1))))
    val second =
      base.copy(attributes = base.attributes.copy(familyFields = Some(PosFixtures.SecondFamilyAttributeFields(1))))
    assert(
      first.payloadId != second.payloadId,
      "the contract is per-family and the identifier space is shared, so the derivation and not the family is " +
        "what has to separate them"
    )
  }

  /** ==The collision an OMITTED contribution admits, constructed rather than
    * argued==
    *
    * [[PayloadBuildRequest.payloadId]] folds the family contribution in
    * unconditionally, and the reason is that the parent beacon block root ahead
    * of it is optional and the same width. Contribute only when there are
    * family fields, and a request carrying a root and no family lays down the
    * same trailing thirty-two bytes as a request carrying a family and no root.
    *
    * That pair is buildable, so it is built: the root is set to exactly what
    * the other request's family contributes. **This passes today and fails the
    * moment the contribution becomes conditional**, which is what the paragraph
    * on its own could not do.
    *
    * The root is read from [[PayloadBuildRequest.familyContribution]] rather
    * than recomputed here. Recomputing it would copy that method's byte layout
    * into this file, where changing the layout would leave this property green
    * and no longer constructing a collision.
    */
  property("a beacon root equal to what a family contributes does not collide with it") {
    val withFamily = PayloadBuildRequest(
      head,
      PosFixtures.attributesWithWithdrawals.copy(familyFields = Some(PosFixtures.SampleFamilyAttributeFields(3)))
    )
    val withRootInstead = PayloadBuildRequest(
      head,
      PosFixtures.bareAttributes.copy(
        appended = Some(
          AttributesWithdrawals(
            Seq(PosFixtures.withdrawal),
            Some(AttributesBeaconRoot(withFamily.familyContribution))
          )
        )
      )
    )
    assert(
      withFamily.payloadId != withRootInstead.payloadId,
      "a contribution folded in only when there are family fields would make these two the same build"
    )
  }
