package org.fukuii.consensus.pos

import java.nio.charset.StandardCharsets

import org.fukuii.bytes.Hash
import org.fukuii.crypto.Sha256
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.Withdrawal

/** What a consensus layer asked to have built: the block to build on, and the
  * attributes to build it to.
  *
  * ==Two fields, because the attributes do not name the parent==
  *
  * A build request arrives as `engine_forkchoiceUpdated`, whose forkchoice
  * state names the head and whose attributes describe the block to follow it
  * (`ethereum/execution-apis` @ `6570b55` `src/engine/paris.md:195-196`). So
  * the parent is the head the same call named, and neither half is derivable
  * from the other.
  */
final case class PayloadBuildRequest(head: Hash, attributes: PayloadAttributes):

  /** The identifier this build is known by.
    *
    * ==Derived from the parameters, which is what both clients do and what
    * buys idempotence==
    *
    * The specification requires only that *"every new build process MUST be
    * uniquely identified by the returned `payloadId` value"*
    * (`ethereum/execution-apis` @ `6570b55` `src/engine/paris.md:142`), which a
    * counter would satisfy. Neither surveyed client uses one.
    * `ethereum/go-ethereum` @ `02872e9ef` hashes the build parameters with
    * SHA-256 and keeps the first eight bytes
    * (`miner/payload_building.go:55-75`); `besu-eth/besu` @ `b330564a94` folds
    * them together with shifts and exclusive-ors
    * (`consensus/merge/.../PayloadIdentifier.java:56-94`), and says why it folds
    * all of them rather than the obvious two: *"normally timestamp and
    * parentHash should be enough to uniquely identify a payload but in special
    * cases, reorgs, CL configuration changes (feeRecipient), or other edge case
    * reasons CL may change other params"*.
    *
    * What derivation buys over a counter is that **the same request asked twice
    * gets the same identifier**, so a consensus layer repeating itself does not
    * start a second build. A counter would answer two identifiers for one
    * build and leave the first stranded in the store until it was evicted.
    *
    * ==go-ethereum's shape, with its version byte deliberately left out==
    *
    * That client overwrites byte zero with the payload version
    * (`miner/payload_building.go:73`) and reads it back to decide which
    * versions an identifier may be served by (`beacon/engine/types.go:204`).
    * Two reasons not to follow it. It is that client's private encoding, which
    * [[PayloadId]] already declines to read for the reason recorded there. And
    * it is unnecessary: `engine_getPayload` refuses by the **timestamp of the
    * built payload** rather than by anything in the identifier
    * (`src/engine/prague.md:81`), so nothing here needs to recover a version
    * from one — and spending eight of sixty-four bits on it narrows the only
    * property the specification asks for.
    *
    * ==Why the concatenation is unambiguous, and where that comes from==
    *
    * Every fixed-width part contributes its full width, and a withdrawal list
    * contributes its RLP, which declares its own length. The one combination
    * that could otherwise collide — no withdrawals but a parent beacon root,
    * whose thirty-two bytes could equal some list's encoding — **cannot be
    * built**: [[PayloadAttributes]]'s appended chain puts the beacon root
    * behind the withdrawals, so a value carrying the second without the first
    * does not exist. The chain was built to exclude states the protocol does
    * not define, and this is a second thing it excludes.
    *
    * ==A family's own appended attributes are folded in, at a FIXED width==
    *
    * They have to be folded in at all for the reason
    * [[PayloadAttributes.FamilyFields.identityBytes]] states: a parameter
    * outside the preimage is one two builds can differ in while sharing an
    * identifier, and the second build would then be served the first one's
    * payload. `ethereum-optimism/op-geth` @ `7da4560d1` folds its own five
    * appended attributes into the same kind of preimage
    * (`miner/payload_building.go:62-98`).
    *
    * **What is not available here is appending them raw at their own widths.**
    * That form needs the set of parts to be known, so that no two combinations
    * of present and absent lay down the same bytes — an argument a client can
    * make about attributes it defines itself and this one cannot make about
    * bytes an unknown family returns. So the contribution is
    * [[familyContribution]]: a SHA-256, unconditionally thirty-two wide, and
    * present even where there are no family fields at all.
    *
    * That is what keeps the argument above intact: after the withdrawals RLP,
    * which declares its own length, what remains is thirty-two bytes or
    * sixty-four, and the two are told apart by the length rather than by
    * anything inside them.
    *
    * **Unconditional is what makes that work, and omitting it when there are no
    * family fields would break it.** The beacon root is optional and also
    * thirty-two wide, so a remainder of thirty-two would then mean either a
    * root with no family contribution or a family contribution with no root —
    * two different requests with one preimage. That pair is constructible, so
    * it is a property beside this rather than a paragraph: a beacon root set to
    * what a family contributes must still not collide with it.
    *
    * ==Truncation, and what it costs==
    *
    * Sixty-four bits, because that is the width the specification fixes. Two
    * distinct builds collide at around 2^32 of them by the birthday bound,
    * which is far beyond a node's lifetime of slots and is a property of the
    * field the protocol chose rather than of this derivation.
    */
  def payloadId: PayloadId =
    val withdrawals = attributes.withdrawals.map(preimageOf).getOrElse(IArray.empty[Byte])
    val beaconRoot = attributes.parentBeaconBlockRoot.map(_.toBytes).getOrElse(IArray.empty[Byte])
    val preimage =
      head.toBytes ++
        attributes.timestamp.toBytes ++
        attributes.prevRandao.toBytes ++
        attributes.suggestedFeeRecipient.toBytes ++
        withdrawals ++
        beaconRoot ++
        familyContribution.toBytes
    PayloadId.fromBytes(Sha256.hash(preimage).toBytes.take(PayloadId.Width)).toOption.get

  /** The thirty-two bytes a family's own appended attributes contribute.
    *
    * ==Absent and empty are different, and hashing the bytes alone made them
    * the same==
    *
    * A leaf returning no bytes is the shape this seam's own contract invites —
    * a marker case a family matches on, carrying nothing. Folding
    * `identityBytes` directly gave it the same digest as having no family
    * fields at all, so a network whose leaf carried no data minted one
    * identifier for two different requests, [[PayloadStore]] replaced on it,
    * and `engine_getPayload` served the wrong block. **That is the same hazard
    * the contribution was added to close, reached through the fix.**
    *
    * A leading byte separates the two cases and costs nothing: absence hashes
    * one byte that no present case can produce, because a present case always
    * hashes at least thirty-three.
    *
    * **The two separations below OVERLAP, and neither is decoration.** The type
    * fold alone would already separate them — a present case hashes at least
    * the thirty-two bytes of a type digest where absence hashes none — so with
    * both in place, removing the leading byte breaks no property. Removing the
    * TYPE fold instead leaves the leading byte carrying that separation by
    * itself. Each backs the other up against the other's removal, which is why
    * the byte stays: it is what a reader tidying the type fold away would
    * otherwise take with it.
    *
    * ==The leaf's own type is folded in as well, and it is not the same
    * question==
    *
    * [[PayloadAttributes.FamilyFields.identityBytes]] asks a family to
    * distinguish its own values from each other, which is all a family can
    * promise: it does not know what any other family returns. **The identifier
    * space is shared and the contract is per-family**, so two families whose
    * leaves happened to return the same bytes would collide with both of them
    * keeping their promise.
    *
    * The leaf's fully-qualified class name closes it without asking a family
    * for anything and without a family being able to forget. It is hashed to a
    * fixed thirty-two bytes first, so that a long name and a short one followed
    * by the start of some contribution cannot lay down the same preimage.
    *
    * **It is a build-local name, and that is sufficient here.** An identifier
    * is minted by this node, handed to the consensus layer, and handed back to
    * this node — nothing compares one across two builds, so a rename moving
    * every identifier for a family costs nothing. [[PayloadId]] carries the
    * evidence for that: nothing specifies the derivation, and one of the two
    * clients read for it reads a private tag out of its own identifier. A
    * build-local input is the kind of input this field already takes, rather
    * than one that happens to be harmless in it.
    *
    * ==`private[pos]` rather than hidden==
    *
    * A property beside this constructs the collision an omitted contribution
    * would admit, by setting a parent beacon block root to exactly what a
    * family contributes. Naming the value is what lets it do that without
    * copying this method's own byte layout into a test, where a change here
    * would leave the test passing and no longer constructing anything.
    */
  private[pos] def familyContribution: Hash =
    attributes.familyFields match
      case None         => Sha256.hash(IArray(PayloadBuildRequest.NoFamilyFields))
      case Some(fields) =>
        Sha256.hash(
          IArray(PayloadBuildRequest.SomeFamilyFields) ++ typeIdentityOf(fields) ++ fields.identityBytes
        )

  private def typeIdentityOf(fields: PayloadAttributes.FamilyFields): IArray[Byte] =
    val name = fields.getClass.getName.getBytes(StandardCharsets.UTF_8)
    Sha256.hash(IArray.unsafeFromArray(name)).toBytes

  private def preimageOf(withdrawals: Seq[Withdrawal]): IArray[Byte] =
    RlpCodec.encodeTo(withdrawals)

object PayloadBuildRequest:

  /** The byte [[PayloadBuildRequest.familyContribution]] hashes where there are
    * no family fields.
    *
    * One byte alone, so it cannot be the prefix of any present case: those
    * carry the other byte and thirty-two more behind it.
    */
  private val NoFamilyFields: Byte = 0x00

  /** The byte that opens a present family's contribution. */
  private val SomeFamilyFields: Byte = 0x01

/** Building the block a consensus layer asked for.
  *
  * ==NOTHING HERE IMPLEMENTS THIS, AND THAT IS THE FINDING==
  *
  * A payload is a block: it needs transactions selected from a pool, executed
  * against the parent's state, and a state root taken over the result. **This
  * build has no block producer**, and no mempool, and no chain to read a parent
  * state from. So there is no implementation to ship and one written now would
  * be a stub answering plausible empty blocks — which is worse than none,
  * because every assertion about a build succeeding would pass and go on
  * passing after a real producer started refusing things.
  *
  * ==What the surveyed clients do is specialize, not write==
  *
  * Neither reaches for a new producer. `ethereum/go-ethereum` @ `02872e9ef`
  * drives its existing miner — `miner/payload_building.go` builds an empty
  * block first and then keeps updating it *"in order to maximize the
  * revenue"* (`:77-81`) — and `besu-eth/besu` @ `b330564a94` keeps improving
  * one proposal per identifier and serves the best
  * (`PostMergeContext.java:241-302`). **Both are a block producer told to
  * produce to a particular set of attributes.**
  *
  * That is why this is a seam rather than a class: what fills it is a producer
  * this build does not have, and the shape of the call is all that can be
  * settled now.
  *
  * ==What IS settled, and it is not nothing==
  *
  * [[PayloadBuildRequest.payloadId]] is a real derivation and is exercised, so
  * the identifier a build is known by does not wait on the producer. Neither
  * does [[PayloadStore]], which is where a produced payload is collected from.
  * **The gap is the middle step alone**, and naming it here is what keeps it
  * from being filled by accident.
  */
trait PayloadFactory:

  /** Start building, and answer the identifier the result will be collected by.
    *
    * The identifier is [[PayloadBuildRequest.payloadId]] and an implementation
    * does not choose it: a consensus layer repeating a request must be given
    * the same one, which is the whole reason it is derived rather than issued.
    *
    * An implementation makes the payload available in a [[PayloadStore]] before
    * it is collected, and may replace it there as the build improves — which is
    * the behavior [[PayloadStore.put]] is written for.
    */
  def begin(request: PayloadBuildRequest): PayloadId
