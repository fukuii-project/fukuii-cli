package org.fukuii.consensus.pos

import org.fukuii.bytes.{Address, Hash, UInt64}
import org.fukuii.types.Withdrawal

/** What the consensus layer asks for when it wants a block built: three fields
  * that have been there since the merge, then a chain of fields each later
  * proposal appended.
  *
  * ==It describes a block that does not exist yet==
  *
  * Every field here is an instruction rather than an observation, which is why
  * this is not a partly-filled [[ExecutionPayload]]. The specification says so
  * of the fee recipient in particular: the built payload *"MAY deviate the
  * `feeRecipient` field value from what is specified by the
  * `suggestedFeeRecipient` parameter"* (`ethereum/execution-apis` @ `6570b55`
  * `src/engine/paris.md:133`), which is why that one field carries `suggested`
  * in its name and the others do not.
  *
  * ==The chain, and why it is the same shape as the payload's==
  *
  * The versions are strict prefixes here too, and the specification enforces the
  * match with its own error rather than the payload's: `-38003: Invalid payload
  * attributes` where the wrong structure is used
  * (`src/engine/shanghai.md:126`), and `-38005: Unsupported fork` where the
  * structure is right and the timestamp is not
  * (`src/engine/cancun.md:145`). So attributes carrying a parent beacon root and
  * no withdrawals belong to no version and no `engine_forkchoiceUpdatedVN` can
  * carry them.
  *
  * **Both clients read for this hold one flat record instead**, and their own
  * shape is what argues for the chain rather than against it:
  * `ethereum/go-ethereum` @ `02872e9ef` `beacon/engine/types.go:70-78` and
  * `besu-eth/besu` @ `b330564a94`
  * `ethereum/api/.../EnginePayloadAttributesParameter.java:29-35` each declare
  * the same seven fields with the trailing four nullable, so each admits sixteen
  * states where those four fields define four. Neither client is wrong about
  * the semantics; both check the combination somewhere other than the type.
  *
  * ==The last two links are absent because the forks that define them are not
  * scheduled==
  *
  * `PayloadAttributesV4` appends a slot number and a target gas limit and
  * `PayloadAttributesV5` appends an inclusion list, and the forks introducing
  * them are drafted rather than activated. Adding a link is a change to this
  * chain, made by whoever schedules that fork — the same rule
  * [[org.fukuii.types.BlockHeader]] states for its own.
  *
  * @param timestamp
  *   the value the built payload must carry, and the input every version gate
  *   in the specification reads. It is not a hint.
  * @param prevRandao
  *   the randomness the built payload must carry.
  * @param suggestedFeeRecipient
  *   whom to credit, subject to the deviation the specification permits above.
  */
final case class PayloadAttributes(
    timestamp: UInt64,
    prevRandao: Hash,
    suggestedFeeRecipient: Address,
    appended: Option[AttributesWithdrawals] = None,
    familyFields: Option[PayloadAttributes.FamilyFields] = None
):

  def withdrawals: Option[Seq[Withdrawal]] = appended.map(_.withdrawals)

  def parentBeaconBlockRoot: Option[Hash] = appended.flatMap(_.next).map(_.parentBeaconBlockRoot)

  /** Which `PayloadAttributesVN` of the specification this value is.
    *
    * **Not the `engine_forkchoiceUpdatedVN` method version.** The two coincide
    * up to V3 and then stop: `engine_forkchoiceUpdatedV3` serves Cancun and
    * Prague both — *"Return `-38005: Unsupported fork` if
    * `payloadAttributes.timestamp` doesn't fall within the time frame of the
    * Cancun *or Prague* forks"* (`src/engine/prague.md:99`) — while the payload
    * axis moved to `engine_newPayloadV4` at the same fork. A single "engine
    * version for this fork" is therefore wrong from Prague onward.
    */
  def structureVersion: Int = 1 + AttributesWithdrawals.lengthOf(appended)

/** The field EIP-4895 appended.
  *
  * The specification gives this the same type the payload's does — an array of
  * `WithdrawalV1` — so the two are the same list asked for and then reported,
  * not a request and a commitment to it.
  */
final case class AttributesWithdrawals(withdrawals: Seq[Withdrawal], next: Option[AttributesBeaconRoot] = None)

/** The field EIP-4788 appended.
  *
  * On the payload side the same value travels as a separate argument to
  * `engine_newPayload` rather than inside the payload, which is why
  * [[ExecutionPayload]] has no matching link and this asymmetry is a property of
  * the protocol rather than of this modeling.
  */
final case class AttributesBeaconRoot(parentBeaconBlockRoot: Hash)

object AttributesWithdrawals:

  /** How many links the chain contributes, counted by walking it. */
  def lengthOf(appended: Option[AttributesWithdrawals]): Int = appended match
    case None    => 0
    case Some(w) => 1 + (if w.next.isEmpty then 0 else 1)

object PayloadAttributes:

  /** Fields a network family appends to the build request for itself.
    *
    * ==This is the seam a rollup already uses, measured rather than
    * anticipated==
    *
    * `ethereum-optimism/op-geth` @ `7da4560d1` carries a `PayloadAttributes`
    * whose first seven fields are byte-identical to upstream go-ethereum's —
    * diffed at `beacon/engine/types.go:67-74` against `:70-78`, with the same
    * extraction over `ExecutableData` differing, so the comparison
    * discriminates — and then appends five of its own at `:77-89`: a forced
    * transaction list, a mempool switch, an exact gas limit, encoded fee-market
    * parameters, and a minimum base fee.
    *
    * It appends them by forking the struct, because there is no other way in:
    * the alternative representation, besu's version chain, is
    * `sealed ... permits` and refuses extension outside its own package
    * (`besu-eth/besu` @ `b330564a94`
    * `ethereum/api/.../PayloadAttributesV1.java:24`). **A single binary serving
    * more than one network family cannot fork the struct**, so the slot is what
    * takes the place of the fork.
    *
    * ==What is deliberately NOT here==
    *
    * Any of those five fields. This build implements no rollup; the seam exists
    * so that building one later is an addition rather than a change to the type
    * every verb in this module takes.
    *
    * ==One member, and it is required rather than optional==
    *
    * The core reads nothing else from a family's fields: a family leaf declares
    * its own case and recovers it by matching on that case. It does read
    * [[FamilyFields.identityBytes]], because
    * [[PayloadBuildRequest.payloadId]] derives a build's identifier from the
    * parameters that describe it — and a parameter left out of that derivation
    * is one two different builds can differ in while sharing an identifier.
    *
    * **Required, because optional is the same defect with a longer fuse.** A
    * family that appended parameters and did not contribute them would get a
    * collision reported by nothing: the second build would resolve to the first
    * one's entry in the store, and the payload served would be built to
    * somebody else's attributes.
    *
    * `ethereum-optimism/op-geth` @ `7da4560d1` folds exactly its own five
    * appended attributes into the same SHA-256 preimage after the
    * specification's parameters, under the comment *"extend if extra payload
    * attributes are used"* (`miner/payload_building.go:62-98`). So the shape is
    * the surveyed one; what differs is that a required member is what makes
    * "extend" something the compiler asks for rather than something a family
    * has to remember.
    *
    * ==The payload's own family slot has no counterpart, deliberately==
    *
    * [[ExecutionPayload.FamilyFields]] stays empty. A payload is a block that
    * has already been built and is identified by its block hash, which is a
    * commitment over the header rather than over the fields a family appends —
    * so there is nothing for a contribution to protect.
    */
  trait FamilyFields:

    /** What this family contributes to a build identifier's preimage.
      *
      * ==Any bytes, provided the same attributes always give the same ones==
      *
      * The derivation hashes them, so neither their length nor their encoding
      * matters to it — [[PayloadBuildRequest.payloadId]] takes a fixed-width
      * digest of whatever is returned, which is what keeps its own preimage
      * unambiguous without knowing anything about this value.
      *
      * What DOES matter is that two family values a network would treat as
      * different attributes return different bytes, and that one value returns
      * the same bytes every time. A field left out here is a field two builds
      * can differ in and share an identifier.
      */
    def identityBytes: IArray[Byte]
