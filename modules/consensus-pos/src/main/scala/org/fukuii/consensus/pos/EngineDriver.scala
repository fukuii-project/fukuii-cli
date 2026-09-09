package org.fukuii.consensus.pos

import org.fukuii.bytes.{Bytes, Hash, UInt256}

/** A `engine_newPayload` call: the payload, and the arguments later versions
  * added beside it.
  *
  * ==Why the added arguments are here rather than on the payload==
  *
  * They are method parameters, not payload fields.
  * `engine_newPayloadV3` takes an `ExecutionPayloadV3` and then two more
  * arguments (`ethereum/execution-apis` @ `6570b55`
  * `src/engine/cancun.md:98-100`), and `engine_newPayloadV4` takes the same
  * payload structure and a fourth (`prague.md:35-38`). Folding them into
  * [[ExecutionPayload]] would make its `structureVersion` answer for something
  * the structure does not carry, and the specification's own `-32602` check
  * against the wrong structure would stop meaning what it says.
  *
  * ==The same chain discipline as the payload's own, for the same reason==
  *
  * The versions are strict prefixes here too — V3 adds two arguments together
  * and V4 adds a third — so independent options would admit combinations no
  * version defines. One example is the whole argument: a request carrying
  * execution requests but no parent beacon root belongs to no version and can
  * be sent by no consensus layer.
  */
final case class NewPayloadRequest(payload: ExecutionPayload, appended: Option[BlobAndBeaconArguments] = None):

  def expectedBlobVersionedHashes: Option[Seq[Hash]] = appended.map(_.expectedBlobVersionedHashes)

  def parentBeaconBlockRoot: Option[Hash] = appended.map(_.parentBeaconBlockRoot)

  def executionRequests: Option[Seq[Bytes]] = appended.flatMap(_.next).map(_.requests)

/** The two arguments EIP-4844 and EIP-4788 added together at
  * `engine_newPayloadV3`.
  *
  * One link rather than two because the specification adds them in one step and
  * no version takes one without the other.
  */
final case class BlobAndBeaconArguments(
    expectedBlobVersionedHashes: Seq[Hash],
    parentBeaconBlockRoot: Hash,
    next: Option[ExecutionRequestsArgument] = None
)

/** The argument EIP-7685 added at `engine_newPayloadV4`.
  *
  * Carried as opaque byte strings for the reason
  * [[ExecutionPayload.transactions]] is: the specification states ordering and
  * length requirements over them and requires `-32602: Invalid params` where
  * they are violated (`prague.md:38`), so the malformed input has to be a value
  * this type can hold.
  */
final case class ExecutionRequestsArgument(requests: Seq[Bytes])

/** What `engine_forkchoiceUpdated` answers.
  *
  * ==Two fields, and the second is present exactly when a build began==
  *
  * The specification's response object is `{payloadStatus, payloadId}` with the
  * identifier `DATA|null` (`src/engine/paris.md:202-206`), and every response it
  * enumerates pairs a `null` identifier with every outcome except one — a
  * `VALID` status where *"the build process has begun"* (`:240`).
  *
  * The pairing is not modeled as a sum here, unlike [[PayloadStatus]]'s own,
  * because the two fields are independent facts rather than one fact with
  * alternatives: the status describes the head the caller named and the
  * identifier describes a build it may or may not have asked for. A caller can
  * legitimately receive `VALID` with no identifier by supplying no attributes.
  */
final case class ForkchoiceUpdatedResult(payloadStatus: PayloadStatus, payloadId: Option[PayloadId])

/** What `engine_getPayload` answers.
  *
  * ==Deliberately smaller than the specification's response==
  *
  * The full response also carries a blobs bundle, a builder-override
  * suggestion, and from `engine_getPayloadV4` the execution requests
  * (`src/engine/prague.md:69-73`). None of them is here, because none can be
  * produced without the machinery that builds a payload, and inventing fields
  * ahead of it would put plausible empty values where a caller would read them
  * as answers.
  *
  * @param blockValue
  *   what the fee recipient is expected to receive, in wei. Absent for
  *   `engine_getPayloadV1`, whose response is the bare payload — the field
  *   arrives with V2 (`src/engine/shanghai.md:159`).
  */
final case class BuiltPayload(executionPayload: ExecutionPayload, blockValue: Option[UInt256] = None)

/** Why `engine_newPayload` refused before it validated anything.
  *
  * Both cases precede execution, and the specification gives them different
  * wire numbers — `-38005` for the fork and `-32602` for the structure
  * (`src/engine/shanghai.md:99`). They are separate cases here for that reason:
  * a caller told only "refused" cannot tell a consensus layer whether to change
  * the method it called or the value it sent.
  */
enum NewPayloadRefusal:

  case ForkRefused(refusal: ForkGateRefusal)

  /** The payload's structure is not the one this timestamp requires.
    *
    * @param required
    *   the `ExecutionPayloadVN` the schedule demands at this timestamp.
    * @param supplied
    *   the version the payload actually is, read off its own appended chain.
    */
  case WrongPayloadStructure(required: Int, supplied: Int)

/** Why `engine_forkchoiceUpdated` refused before it did anything.
  *
  * The attributes case is `-38003: Invalid payload attributes` rather than
  * `-32602` (`src/engine/shanghai.md:126`), which is why it is not the same case
  * as [[NewPayloadRefusal.WrongPayloadStructure]] under another name.
  */
enum ForkchoiceUpdatedRefusal:

  case ForkRefused(refusal: ForkGateRefusal)

  case WrongAttributesStructure(required: Int, supplied: Int)

/** Why `engine_getPayload` refused.
  *
  * `UnknownPayload` is `-38001` and is the one refusal here that is not about a
  * fork: *"The call MUST return `-38001: Unknown payload` error if the build
  * process identified by the `payloadId` does not exist"*
  * (`src/engine/paris.md:265`).
  */
enum GetPayloadRefusal:

  case ForkRefused(refusal: ForkGateRefusal)

  case UnknownPayload(payloadId: PayloadId)

/** The three verbs an in-process consensus layer calls.
  *
  * ==In-process on purpose, and that is what makes it the analogue of a
  * consensus engine rather than of a server==
  *
  * Nothing here mentions a transport. The Engine API is carried over
  * authenticated HTTP in production, but authentication, framing and JSON are
  * properties of the carrier rather than of the contract, and a driver that
  * took them as arguments could not be exercised without standing one up.
  *
  * **So the refusals above are domain values and never wire numbers.** Each
  * specification citation on them names the number the transport will attach;
  * attaching it is that layer's step, and the mapping is the only thing it adds.
  *
  * ==The version is a parameter rather than a method per version==
  *
  * `besu-eth/besu` @ `b330564a94` gives each version its own class
  * (`ethereum/api/.../engine/EngineNewPayloadV1.java` through `V5`), which is
  * what a registry keyed on method names needs. There is no registry here, and
  * three typed enums carry the version without fifteen near-identical methods —
  * [[NewPayloadVersion]] states why they are three types and not one integer.
  */
trait EngineDriver:

  /** Offer a payload for validation.
    *
    * The fork gate and the structure check both run before any of it is
    * executed, which is the specification's own order: `newPayloadV3`'s
    * numbered steps put the parameter check first and the fork check second,
    * ahead of every validation over the payload's contents
    * (`src/engine/cancun.md:111-113`).
    */
  def newPayload(
      version: NewPayloadVersion,
      request: NewPayloadRequest
  ): Either[NewPayloadRefusal, PayloadStatus]

  /** Name the canonical head, and optionally ask for a block to be built.
    *
    * @param attributes
    *   absent where the caller is only naming a head. The specification types
    *   the parameter `Object|null` (`src/engine/paris.md:196`), and the fork
    *   gate reads the attributes' timestamp rather than the head's — so a call
    *   supplying none has no timestamp to gate on and cannot be refused for a
    *   fork.
    */
  def forkchoiceUpdated(
      version: ForkchoiceUpdatedVersion,
      state: ForkchoiceState,
      attributes: Option[PayloadAttributes]
  ): Either[ForkchoiceUpdatedRefusal, ForkchoiceUpdatedResult]

  /** Collect a payload a previous build request began.
    *
    * The fork gate reads the timestamp of the payload that was built, not of
    * the request that asked for it — *"if the `timestamp` of the built payload
    * does not fall within the time frame"* (`src/engine/prague.md:81`). The two
    * differ whenever a build is collected after a fork boundary it was started
    * before.
    */
  def getPayload(
      version: GetPayloadVersion,
      payloadId: PayloadId
  ): Either[GetPayloadRefusal, BuiltPayload]
