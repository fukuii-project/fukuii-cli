package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64

/** The verb contract, called the way a consensus layer calls it: in process,
  * with no transport, by a test playing the consensus layer.
  *
  * The driver under test gates and does nothing else — see
  * [[PosFixtures.GatingDriver]] for why it answers `SYNCING` rather than
  * `VALID`. So every assertion here is about the contract and the gate reaching
  * each other, never about a payload being correct.
  */
class EngineDriverSpec extends AnyFlatSpec:

  private val S = PosFixtures.Schedules
  private val driver = PosFixtures.GatingDriver(EngineForkGate(S.activated))

  private def payloadAt(seconds: Long, structure: ExecutionPayload): ExecutionPayload =
    structure.copy(timestamp = UInt64.fromBits(seconds))

  private def attributesAt(seconds: Long, structure: PayloadAttributes): PayloadAttributes =
    structure.copy(timestamp = UInt64.fromBits(seconds))

  private val cancunPayload = payloadAt(S.cancun, PosFixtures.withBlobGas)
  private val cancunAttributes = attributesAt(S.cancun, PosFixtures.attributesWithBeaconRoot)

  "EngineDriver.newPayload" should "accept a payload whose version and structure both fit the upgrade" in
    assert(
      driver.newPayload(NewPayloadVersion.V3, NewPayloadRequest(cancunPayload)) == Right(PayloadStatus.Syncing),
      "the version serves Cancun and the payload is an ExecutionPayloadV3, which is what Cancun requires"
    )

  it should "refuse a version that does not serve the payload's upgrade" in
    assert(
      driver.newPayload(NewPayloadVersion.V2, NewPayloadRequest(cancunPayload)).isLeft,
      "a Cancun payload offered to the version Cancun superseded is the boundary refusal, reached through the verb"
    )

  it should "name the fork as the reason where the version is wrong" in
    assert(
      driver.newPayload(NewPayloadVersion.V2, NewPayloadRequest(cancunPayload)) match
        case Left(NewPayloadRefusal.ForkRefused(_)) => true
        case _                                      => false,
      "the consensus layer has to change the method it called, which a structure refusal would not tell it"
    )

  it should "refuse a payload of the wrong structure for its own timestamp" in
    assert(
      driver.newPayload(
        NewPayloadVersion.V3,
        NewPayloadRequest(payloadAt(S.cancun, PosFixtures.withWithdrawals))
      ) == Left(NewPayloadRefusal.WrongPayloadStructure(3, 2)),
      "the version was right and the value was not, which is a different wire number and a different remedy"
    )

  it should "accept a blob-parameter-only timestamp on the version that served the upgrade before it" in
    assert(
      driver.newPayload(NewPayloadVersion.V4, NewPayloadRequest(payloadAt(S.bpo1, PosFixtures.withBlobGas))).isRight,
      "no version moves at a blob-parameter-only upgrade, so the interval covers it with no entry naming it"
    )

  "EngineDriver.forkchoiceUpdated" should "accept attributes whose version and structure fit the upgrade" in
    assert(
      driver
        .forkchoiceUpdated(
          ForkchoiceUpdatedVersion.V3,
          PosFixtures.forkchoiceState,
          Some(cancunAttributes)
        )
        .isRight,
      "the version serves Cancun and the attributes are a PayloadAttributesV3"
    )

  it should "gate on the attributes' timestamp and not on the head" in
    assert(
      driver
        .forkchoiceUpdated(
          ForkchoiceUpdatedVersion.V2,
          PosFixtures.forkchoiceState,
          Some(cancunAttributes)
        )
        .isLeft,
      "the head is three hashes and carries no timestamp, so the attributes are the only thing that can be gated"
    )

  it should "refuse nothing where no build was asked for" in
    assert(
      driver.forkchoiceUpdated(ForkchoiceUpdatedVersion.V2, PosFixtures.forkchoiceState, None).isRight,
      "a call naming only a head supplies no timestamp, so there is nothing for a fork gate to compare"
    )

  it should "answer no payload identifier where no build was asked for" in
    assert(
      driver
        .forkchoiceUpdated(ForkchoiceUpdatedVersion.V2, PosFixtures.forkchoiceState, None)
        .map(_.payloadId) == Right(None),
      "an identifier names a build process, and none was started"
    )

  it should "refuse attributes of the wrong structure for their own timestamp" in
    assert(
      driver.forkchoiceUpdated(
        ForkchoiceUpdatedVersion.V3,
        PosFixtures.forkchoiceState,
        Some(attributesAt(S.cancun, PosFixtures.attributesWithWithdrawals))
      ) == Left(ForkchoiceUpdatedRefusal.WrongAttributesStructure(3, 2)),
      "the attributes carry their own error number, so this is not the payload's refusal under another name"
    )

  "EngineDriver.getPayload" should "refuse an identifier no build produced" in
    assert(
      driver.getPayload(GetPayloadVersion.V3, PosFixtures.payloadId) ==
        Left(GetPayloadRefusal.UnknownPayload(PosFixtures.payloadId)),
      "the identifier is echoed back, which is what lets a caller tell which of several builds it asked about"
    )

  "NewPayloadRequest" should "carry no added arguments at the versions that define none" in
    assert(
      NewPayloadRequest(cancunPayload).parentBeaconBlockRoot.isEmpty,
      "the arguments arrive with the version that added them, exactly as the payload's own fields do"
    )

  it should "carry the two arguments added together" in
    assert(
      NewPayloadRequest(
        cancunPayload,
        Some(BlobAndBeaconArguments(Seq(PosFixtures.hash(0x01)), PosFixtures.hash(0x02)))
      ).parentBeaconBlockRoot.contains(PosFixtures.hash(0x02)),
      "no version takes one of the pair without the other"
    )

  it should "carry the execution requests only behind the pair that precedes them" in
    assert(
      NewPayloadRequest(
        cancunPayload,
        Some(
          BlobAndBeaconArguments(
            Seq(PosFixtures.hash(0x01)),
            PosFixtures.hash(0x02),
            Some(ExecutionRequestsArgument(Seq(PosFixtures.requestBytes)))
          )
        )
      ).executionRequests.contains(Seq(PosFixtures.requestBytes)),
      "the chain is a prefix, so a request carrying execution requests carries the beacon root too"
    )
