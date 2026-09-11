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
  *
  * ==The two are NOT equally consumed, and the asymmetry is recorded==
  *
  * [[parentBeaconBlockRoot]] becomes a header field, so a wrong one changes the
  * block hash and is caught by the check every payload goes through.
  * [[expectedBlobVersionedHashes]] becomes nothing: the specification requires
  * it compared against the versioned hashes inside the payload's own blob
  * transactions (`ethereum/execution-apis` @ `6570b55`
  * `src/engine/cancun.md:115-117`), which needs those transactions decoded, and
  * nothing on this path decodes one.
  *
  * **So it is carried and not checked**, which is the one argument on this seam
  * the block-hash check cannot back up.
  * [[TranslationRefusal.BlobVersionedHashesNotChecked]] registers the gap and
  * states what would close it; the field is here because refusing to carry an
  * argument the method defines would be a worse answer than carrying it.
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

/** The three verbs this seam models, driven in process.
  *
  * ==Three is what is MODELED here, and not what a consensus layer calls==
  *
  * Swept across the six consensus-layer clients in this project's corpus, main
  * sources only with test trees excluded and a control on a verb that does not
  * exist returning nothing: `engine_newPayload`, `engine_forkchoiceUpdated`,
  * `engine_getPayload` and **`engine_getBlobs` are each named by all six**.
  * Below them, `engine_getClientVersion` in five, and
  * `engine_exchangeCapabilities` and `engine_getPayloadBodies` in four apiece.
  *
  * So a fourth verb is exactly as universal as these three, and describing this
  * trait as what a consensus layer calls would state something about the
  * calling side that the calling side contradicts.
  *
  * **`engine_getBlobs` is not this trait's, and the reason is ownership rather
  * than readiness.** Three things settle it, and the fork ladder is not one of
  * them.
  *
  * **It is not gated by this fork, and the specification says so in its own
  * words.** `engine_getBlobsV1` is documented in the Cancun document and
  * annotated *"This is a new method introduced after Cancun. It is defined
  * here because it is backwards-compatible with Cancun"* (`ethereum/execution-apis`
  * @ `6570b5500` `src/engine/cancun.md:187`). A previous reading deferred it as
  * living above this project's schedule; that is true of `V2` and `V3`, which
  * `src/engine/osaka.md:94,122` introduce, and false of `V1`. **So reaching
  * Cancun does not bring it into range and does not leave it out of range
  * either** — nothing about a fork decides it.
  *
  * **What it answers is pool state, which is not this layer's.** It serves
  * blobs out of the transaction pool, and the specification leaves what is in
  * that pool to the client: *"execution layer clients may prune old blobs from
  * their pool"* (`:211`), and client software **MAY** *"return an array of all
  * `null` entries if syncing or otherwise unable to serve blob pool data"*
  * (`:209`). A policy that moves no state root and is tunable without a
  * hard fork belongs to whoever owns mempool policy, and the surface it is
  * offered over — the method name, the version, the ordering requirement on
  * the response array, the `-38004: Too large request` code and the one-second
  * timeout — belongs to whoever owns the JSON-RPC namespace. **Neither is this
  * seam**, which owns what a fork selects and what order a driver may call in,
  * and this verb has nothing of either.
  *
  * **And it could still only be typed over values this build does not have.**
  * Its response is `BlobAndProofV1`, a 131,072-byte SSZ blob beside a 48-byte
  * KZG proof (`:77-78`). This fork brought blob GAS and the commitments'
  * versioned hashes; it did not bring a blob.
  * `org.fukuii.types.Transaction.Blob` says so on its own field — *"the blobs
  * themselves travel beside the transaction on the network layer and are not
  * part of it here"* — and no transaction pool is modeled anywhere.
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
