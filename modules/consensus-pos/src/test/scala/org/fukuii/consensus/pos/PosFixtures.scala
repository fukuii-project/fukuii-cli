package org.fukuii.consensus.pos

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.networks.ethereum.{Mainnet, Upgrades}
import org.fukuii.chainspec.{Activation, Network, Upgrade, UpgradeId, UpgradeSchedule}
import org.fukuii.types.{Bloom, Withdrawal}

/** What more than one spec in this module needs and none of them should build
  * twice.
  *
  * Every field a test does not read is left at its zero rather than invented: a
  * plausible value in a field nothing reads suggests something stated it, and
  * nothing here did.
  */
object PosFixtures:

  def hash(byte: Int): Hash = Hash.fromBytesTruncating(IArray.fill(Hash.Width)(byte.toByte))

  def address(byte: Int): Address = Address.fromBytesTruncating(IArray.fill(Address.Width)(byte.toByte))

  val withdrawal: Withdrawal = Withdrawal(UInt64.fromBits(1L), UInt64.fromBits(2L), address(0x03), UInt64.fromBits(4L))

  /** The fourteen fields every version of the structure carries.
    *
    * Named for what it is rather than for the fork it belongs to: it is the
    * whole of an `ExecutionPayloadV1`, and every later version is this plus
    * appended links.
    */
  val bareCore: ExecutionPayload = ExecutionPayload(
    parentHash = hash(0x11),
    feeRecipient = address(0x22),
    stateRoot = hash(0x33),
    receiptsRoot = hash(0x44),
    logsBloom = Bloom.Empty,
    prevRandao = hash(0x55),
    blockNumber = UInt64.fromBits(7L),
    gasLimit = UInt64.fromBits(30000000L),
    gasUsed = UInt64.fromBits(21000L),
    timestamp = UInt64.fromBits(1710338135L),
    extraData = Bytes.Empty,
    baseFeePerGas = UInt256.Zero,
    blockHash = hash(0x66),
    transactions = Seq.empty
  )

  val withWithdrawals: ExecutionPayload =
    bareCore.copy(appended = Some(PayloadWithdrawals(Seq(withdrawal))))

  val withBlobGas: ExecutionPayload =
    bareCore.copy(
      appended = Some(
        PayloadWithdrawals(
          Seq(withdrawal),
          Some(PayloadBlobGas(UInt64.fromBits(131072L), UInt64.fromBits(262144L)))
        )
      )
    )

  val bareAttributes: PayloadAttributes = PayloadAttributes(
    timestamp = UInt64.fromBits(1710338135L),
    prevRandao = hash(0x77),
    suggestedFeeRecipient = address(0x88)
  )

  val attributesWithWithdrawals: PayloadAttributes =
    bareAttributes.copy(appended = Some(AttributesWithdrawals(Seq(withdrawal))))

  val attributesWithBeaconRoot: PayloadAttributes =
    bareAttributes.copy(
      appended = Some(AttributesWithdrawals(Seq(withdrawal), Some(AttributesBeaconRoot(hash(0x99)))))
    )

  /** A stand-in for what a network family would append.
    *
    * It carries a field so that the test reads a value back rather than merely
    * observing that a slot accepted something — an empty case would pass the
    * same assertion whether or not the value survived the round trip.
    */
  final case class SampleFamilyPayloadFields(marker: Int) extends ExecutionPayload.FamilyFields

  /** The attributes counterpart, whose marker is also what it contributes to a
    * build identifier — so a test can change one number and require the
    * identifier to move.
    */
  final case class SampleFamilyAttributeFields(marker: Int) extends PayloadAttributes.FamilyFields:
    def identityBytes: IArray[Byte] = IArray(marker.toByte)

  /** The schedules the fork-gate specs resolve against.
    *
    * ==Fictional network, round timestamps, and both are deliberate==
    *
    * A schedule built here asserts nothing about what any real network runs:
    * the properties under test are the gate's, and a recognizable chain id or a
    * real activation timestamp would invite them to be read as claims about
    * that chain. `ChainspecFixtures` in the module below states the same reason
    * for the same choice.
    *
    * Round numbers a thousand apart also make the boundary arithmetic legible.
    * The one property that matters is that they ascend in the order the Engine
    * API's own upgrades do.
    */
  object Schedules:

    private val network: Network = Network(UInt64.fromBits(900301L), "Probe")

    private def entry(activation: Activation, label: String): UpgradeSchedule.Entry =
      UpgradeSchedule.Entry(activation, UpgradeId.named(network, label), Upgrade.RuleChange(Upgrades.frontier))

    private val genesis: UpgradeSchedule.Entry =
      UpgradeSchedule.Entry(
        Activation.AtBlock(UInt64.Zero),
        UpgradeId.synthesized(network),
        Upgrade.RuleChange(Upgrades.frontier)
      )

    def at(seconds: Long): UInt64 = UInt64.fromBits(seconds)

    val shanghai: Long = 1000L
    val cancun: Long = 2000L
    val prague: Long = 3000L
    val osaka: Long = 4000L
    val bpo1: Long = 4100L
    val bpo2: Long = 4200L
    val amsterdam: Long = 5000L

    private def timestamped(seconds: Long, label: String): UpgradeSchedule.Entry =
      entry(Activation.AtTimestamp(at(seconds)), label)

    private def build(rest: Vector[UpgradeSchedule.Entry]): UpgradeSchedule =
      UpgradeSchedule.of(genesis +: rest).toOption.get

    /** The activated ladder, with the two blob-parameter-only upgrades on it and
      * no Amsterdam — which is every proof-of-stake network's actual state.
      */
    val activated: UpgradeSchedule = build(
      Vector(
        timestamped(shanghai, "Shanghai"),
        timestamped(cancun, "Cancun"),
        timestamped(prague, "Prague"),
        timestamped(osaka, "Osaka"),
        timestamped(bpo1, "BPO1"),
        timestamped(bpo2, "BPO2")
      )
    )

    /** The same, with Amsterdam dated, so an upper bound at the top of the
      * ladder has something to fire against.
      */
    val withAmsterdam: UpgradeSchedule = build(
      Vector(
        timestamped(shanghai, "Shanghai"),
        timestamped(cancun, "Cancun"),
        timestamped(prague, "Prague"),
        timestamped(osaka, "Osaka"),
        timestamped(amsterdam, "Amsterdam")
      )
    )

    /** Shanghai only, so a version bounded below by Cancun has no activation to
      * resolve.
      */
    val withoutCancun: UpgradeSchedule = build(Vector(timestamped(shanghai, "Shanghai")))

    /** Cancun named but not yet dated. */
    val cancunUnscheduled: UpgradeSchedule =
      build(Vector(timestamped(shanghai, "Shanghai"), entry(Activation.Unscheduled, "Cancun")))

    /** Cancun permanently declined by this network. */
    val cancunRefused: UpgradeSchedule =
      build(Vector(timestamped(shanghai, "Shanghai"), entry(Activation.Never, "Cancun")))

    /** The height both wrong-axis fixtures misconfigure their upgrade at.
      *
      * Small deliberately, because that is the arrangement a cross-axis
      * comparison fails silently on: every timestamp a caller could offer
      * exceeds it, so a gate reading the flat point would admit all of them and
      * never fire.
      */
    val wrongAxisHeight: Long = 12L

    /** The activation those fixtures carry, named once so an assertion can
      * state the refusal it expects rather than repeating the literal.
      */
    val wrongAxisActivation: Activation = Activation.AtBlock(UInt64.fromBits(wrongAxisHeight))

    /** Cancun mis-configured onto the block-number axis.
      *
      * **Shanghai is here so the structure ladder can reach the defect.** That
      * ladder walks upward and stops at the first rung admitting the timestamp,
      * so a schedule missing Shanghai never climbs past the first rung and never
      * consults Cancun at all — which is correct behavior and would have made
      * this fixture assert nothing.
      *
      * Cancun sits above genesis and below Shanghai because EIP-6122 requires
      * block activations to precede timestamp ones and
      * [[org.fukuii.chainspec.UpgradeSchedule]] enforces it. The ordering is
      * forced; the misconfiguration under test is the axis.
      *
      * **The defect is at the TOP of the structure ladder here**, so a walk
      * that wrongly continued past it exhausts the rungs and still refuses —
      * with the wrong cause. That makes this fixture unable to catch the walk
      * on its own, which is what [[shanghaiOnTheBlockAxis]] is for.
      */
    val cancunOnTheBlockAxis: UpgradeSchedule =
      build(
        Vector(
          entry(wrongAxisActivation, "Cancun"),
          timestamped(shanghai, "Shanghai")
        )
      )

    /** Shanghai mis-configured onto the block-number axis, with Cancun dated
      * above it.
      *
      * ==The arrangement where walking past a wrong-axis bound ANSWERS==
      *
      * Every structure rung is bounded by Shanghai or by something above it, so
      * a walk that treated the wrong-axis refusal as "not this rung" reaches
      * `[Cancun, Amsterdam)` — whose lower bound is dated and whose upper bound
      * is absent from this schedule and therefore supersedes nothing. It
      * admits, and the ladder returns a structure version over a schedule no
      * version can be served against.
      *
      * That is the direction worth a fixture of its own: a refusal naming the
      * wrong cause is a bad diagnostic, and a `Right` here is a wrong answer.
      */
    val shanghaiOnTheBlockAxis: UpgradeSchedule =
      build(
        Vector(
          entry(wrongAxisActivation, "Shanghai"),
          timestamped(cancun, "Cancun")
        )
      )

    /** Ethereum mainnet's own schedule, as this build currently carries it.
      *
      * Used only where the assertion is about data nobody will edit — Shanghai
      * is history and its activation cannot move. The upgrades after it are not
      * on this schedule yet, so nothing here may assert their absence: that
      * would be a test failing on the commit that adds them, which is a ratchet
      * pointed at legitimate work.
      */
    val ethereumMainnet: UpgradeSchedule = Mainnet.schedule.toOption.get

    /** Where Shanghai activates on that schedule, read out of it rather than
      * copied here.
      *
      * `get` deliberately: an absent Shanghai must fail loudly, because the
      * alternative is a default that turns every assertion resting on this into
      * one that passes without comparing anything.
      */
    val mainnetShanghai: Long =
      EngineForkGate(ethereumMainnet)
        .activationOf(EngineUpgrade.Shanghai)
        .flatMap(_.point)
        .map(_.toBits)
        .get

  /** A driver that does the gating and nothing else.
    *
    * ==What it is for, and what it deliberately does not do==
    *
    * It exists so the verb contract is exercised the way a consensus layer
    * exercises it — in process, with no transport — and so the gate is reached
    * through the verbs rather than only called directly. **It validates
    * nothing**: `newPayload` answers `SYNCING` for every request it admits,
    * which is the specification's own answer where the data needed to validate
    * is missing (`ethereum/execution-apis` @ `6570b55`
    * `src/engine/paris.md:178`), and is true of a driver with no chain behind
    * it rather than a placeholder standing in for one.
    *
    * Answering `VALID` instead would be the dangerous stub: every assertion
    * about a payload being accepted would pass, and would go on passing after
    * real validation arrived and started rejecting things.
    */
  final class GatingDriver(gate: EngineForkGate) extends EngineDriver:

    def newPayload(
        version: NewPayloadVersion,
        request: NewPayloadRequest
    ): Either[NewPayloadRefusal, PayloadStatus] =
      val timestamp = request.payload.timestamp
      for
        _ <- gate.admits(version.window, timestamp).left.map(NewPayloadRefusal.ForkRefused.apply)
        required <- gate.requiredPayloadStructure(timestamp).left.map(NewPayloadRefusal.ForkRefused.apply)
        _ <-
          if required == request.payload.structureVersion then Right(())
          else Left(NewPayloadRefusal.WrongPayloadStructure(required, request.payload.structureVersion))
      yield PayloadStatus.Syncing

    def forkchoiceUpdated(
        version: ForkchoiceUpdatedVersion,
        state: ForkchoiceState,
        attributes: Option[PayloadAttributes]
    ): Either[ForkchoiceUpdatedRefusal, ForkchoiceUpdatedResult] =
      attributes match
        // No attributes means no build was asked for, so there is no timestamp
        // to gate on -- the head named carries its own, and the specification
        // gates this verb on the ATTRIBUTES' timestamp.
        case None         => Right(ForkchoiceUpdatedResult(PayloadStatus.Syncing, None))
        case Some(wanted) =>
          for
            _ <- gate.admits(version.window, wanted.timestamp).left.map(ForkchoiceUpdatedRefusal.ForkRefused.apply)
            required <- gate
              .requiredAttributesStructure(wanted.timestamp)
              .left
              .map(ForkchoiceUpdatedRefusal.ForkRefused.apply)
            _ <-
              if required == wanted.structureVersion then Right(())
              else Left(ForkchoiceUpdatedRefusal.WrongAttributesStructure(required, wanted.structureVersion))
          yield ForkchoiceUpdatedResult(PayloadStatus.Syncing, None)

    /** Every identifier is unknown, because nothing here starts a build.
      *
      * The fork gate is unreachable from this body on purpose: it reads the
      * timestamp of the payload that was BUILT, and no payload has been.
      */
    def getPayload(
        version: GetPayloadVersion,
        payloadId: PayloadId
    ): Either[GetPayloadRefusal, BuiltPayload] =
      Left(GetPayloadRefusal.UnknownPayload(payloadId))

  val forkchoiceState: ForkchoiceState = ForkchoiceState(hash(0xa1), hash(0xa2), hash(0xa3))

  val payloadId: PayloadId = PayloadId.fromBytes(IArray[Byte](1, 2, 3, 4, 5, 6, 7, 8)).toOption.get

  /** One execution-request element: a type byte and some data behind it.
    *
    * Two bytes rather than one, because the specification requires an element be
    * longer than a single byte and rejects one that is not.
    */
  val requestBytes: Bytes = Bytes.fromIArray(IArray[Byte](0x01, 0x2a))
