package org.fukuii.consensus.pos

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Activation, UpgradeId, UpgradeSchedule}

/** Why an engine method version may not serve a payload.
  *
  * ==A domain outcome, not a wire number==
  *
  * The Engine API spells every case below `-38005: Unsupported fork`
  * (`ethereum/execution-apis` @ `6570b55` `src/engine/common.md:101`). That
  * number is the transport's to attach; what this module answers is the
  * distinguishable reason, because the four are not the same fact and a caller
  * that collapsed them could not tell an operator whether to wait, reconfigure
  * or use a different version.
  *
  * ==Every case carries what it compared==
  *
  * A refusal that says only "unsupported fork" sends whoever reads it back to
  * the schedule to work out which bound fired and against what. The activation
  * and the timestamp are both already in hand at the point of refusal, so
  * carrying them costs nothing and is the difference between a diagnosable
  * refusal and a puzzling one.
  */
enum ForkGateRefusal:

  /** The payload predates the upgrade at which this version began serving. */
  case BeforeFirstSupported(upgrade: EngineUpgrade, activatesAt: UInt64, timestamp: UInt64)

  /** The payload is at or after the upgrade that superseded this version.
    *
    * At or after rather than after: the bound is exclusive, so a payload landing
    * exactly on the superseding upgrade's activation belongs to the next
    * version. That boundary is the one an off-by-one silently moves, and it
    * moves it onto a block that exists.
    */
  case AtOrAfterFirstUnsupported(upgrade: EngineUpgrade, activatesAt: UInt64, timestamp: UInt64)

  /** The network's schedule holds no entry for the bounding upgrade.
    *
    * Either it has not been dated yet or the schedule simply does not carry it.
    * The version cannot serve anything until it is, since there is no
    * activation to compare a timestamp against.
    */
  case UpgradeNotScheduled(upgrade: EngineUpgrade)

  /** The network has permanently declined the bounding upgrade.
    *
    * Distinct from [[UpgradeNotScheduled]] and the distinction is the whole
    * point of [[org.fukuii.chainspec.Activation.Never]] existing: a reader who
    * confuses the two waits for an activation that is not coming.
    */
  case UpgradeRefusedByNetwork(upgrade: EngineUpgrade)

  /** The bounding upgrade activates on the block-number axis, so no comparison
    * against a timestamp is meaningful.
    *
    * ==This is a refusal rather than a comparison, and that is the point==
    *
    * Both axes are unsigned 64-bit quantities, so a gate that reached for
    * [[org.fukuii.chainspec.Activation.point]] would get a number, compare it,
    * and answer confidently. Ethereum mainnet's own pre-merge upgrades sit at
    * block heights in the millions while its timestamps are in the billions, so
    * that comparison would admit every payload and never fire — a gate that
    * reports clean forever.
    *
    * Refusing instead makes a misconfigured bound loud at the first call rather
    * than silent for the life of the network.
    */
  case UpgradeNotOnTheTimestampAxis(upgrade: EngineUpgrade, activation: Activation)

  /** The timestamp is at or after an upgrade whose structures this build does
    * not model.
    *
    * Answered rather than guessed. The alternative is to let the newest modeled
    * structure stand in for one it is not, which would accept a payload missing
    * fields the network requires and report it valid.
    */
  case StructureNotModeled(beyond: EngineUpgrade, timestamp: UInt64)

/** Whether an engine method version may serve a payload of a given timestamp,
  * answered against one network's schedule.
  *
  * ==Everything is resolved inside the call, and that is load-bearing==
  *
  * A gate could look each bounding upgrade up once and keep the answer. It does
  * not, and the reason is an incident a production client recorded in its own
  * source. `besu-eth/besu` @ `b330564a94`
  * `ethereum/core/.../DefaultProtocolSchedule.java:57-63` documents a copy
  * constructor that left the milestone map empty, so `milestoneFor` answered
  * absent for every fork — and, verbatim, *"Engine API handlers (e.g.
  * EngineForkchoiceUpdatedV3) cache those Optionals at construction time and
  * then reject every payload at the Cancun/Amsterdam boundary with
  * UNSUPPORTED_FORK."*
  *
  * **Read the root cause precisely, because "never cache" without it is the
  * wrong lesson.** What was cached was not wrong; it was *incomplete at the
  * moment it was taken*, and caching froze a transient emptiness into a
  * permanent refusal. besu's own fix was to populate the map rather than to
  * stop caching.
  *
  * **So this is defense in depth rather than a repair.**
  * [[org.fukuii.chainspec.UpgradeSchedule]] is immutable and can only be built
  * through a constructor that validates it, so there is no window here in which
  * a lookup would be incomplete, and caching would currently be safe. Resolving
  * per call is what keeps it safe if a schedule ever becomes lazily populated —
  * which is exactly the change that produced the incident above, made in a
  * place that had no idea it was load-bearing.
  *
  * The comparison itself is per call regardless: the timestamp is the payload's
  * and no two payloads need share an answer.
  *
  * ==The lookup is by the upgrade's own label==
  *
  * A schedule's entries carry the network's word for each upgrade
  * ([[org.fukuii.chainspec.UpgradeId.Label.Named]]), and
  * [[EngineUpgrade.label]] is the specification's. They coincide for a network
  * that took the upgrade and named it as the specification does, which is what
  * the Engine API assumes throughout. **A network that calls its own upgrade
  * something else is not served by this**, and answers
  * [[ForkGateRefusal.UpgradeNotScheduled]] rather than silently matching
  * something adjacent.
  *
  * `besu-eth/besu` @ `b330564a94` couples the two the same way, one step more
  * safely: its `milestoneFor` is keyed on an enum value rather than on a string
  * (`ethereum/core/.../DefaultProtocolSchedule.java:174-176`). The label is what
  * this build has, because an upgrade identifier here is scoped to a network
  * and the map above is a fact about the Engine API rather than about a
  * network.
  */
final class EngineForkGate(val schedule: UpgradeSchedule):

  private val byPoint: Ordering[UInt64] = summon[Ordering[UInt64]]

  /** Whether a payload or build request with this timestamp falls inside the
    * window.
    *
    * `Right(())` where it does. The lower bound is inclusive and the upper is
    * exclusive, matching `ForkSupportHelper`'s `<` and `>=`
    * (`besu-eth/besu` @ `b330564a94`
    * `ethereum/api/.../ForkSupportHelper.java:32,61`).
    *
    * A window with no lower bound is served from the beginning of the Engine
    * API and its lower half cannot refuse; one with no upper bound has nothing
    * superseding it and its upper half cannot refuse.
    */
  def admits(window: ForkWindow, timestamp: UInt64): Either[ForkGateRefusal, Unit] =
    for
      _ <- window.firstSupported.fold[Either[ForkGateRefusal, Unit]](Right(())) { upgrade =>
        activationTimestampOf(upgrade).flatMap { activatesAt =>
          if byPoint.lt(timestamp, activatesAt) then
            Left(ForkGateRefusal.BeforeFirstSupported(upgrade, activatesAt, timestamp))
          else Right(())
        }
      }
      _ <- window.firstUnsupported.fold[Either[ForkGateRefusal, Unit]](Right(())) { upgrade =>
        supersedingTimestampOf(upgrade).flatMap {
          case Some(activatesAt) if byPoint.gteq(timestamp, activatesAt) =>
            Left(ForkGateRefusal.AtOrAfterFirstUnsupported(upgrade, activatesAt, timestamp))
          case _ => Right(())
        }
      }
    yield ()

  /** Which `ExecutionPayloadVN` the specification requires of a payload with
    * this timestamp.
    *
    * ==Keyed on the timestamp, not on the method version, and the
    * specification is explicit about it==
    *
    * `engine_newPayloadV2` accepts *either* structure —
    * *"`ExecutionPayloadV1` MUST be used if the `timestamp` value is lower than
    * the Shanghai timestamp, `ExecutionPayloadV2` MUST be used if the
    * `timestamp` value is greater or equal"* (`ethereum/execution-apis` @
    * `6570b55` `src/engine/shanghai.md:96-98`) — so the required structure is
    * not a property of the version at all. Two later versions make the same
    * point from the other side: `engine_newPayloadV4` takes an
    * `ExecutionPayloadV3` (`prague.md:35`) and so does `engine_getPayloadV5`
    * (`osaka.md:70`), because those upgrades moved the method and not the
    * structure.
    *
    * **So one ladder answers for every version**, and a caller checks the
    * payload it was handed against it rather than against a per-version
    * expectation that would be wrong three times over.
    *
    * The refusal a mismatch earns is `-32602: Invalid params`
    * (`shanghai.md:99`), which is a different wire number from a fork refusal
    * and a different fact — the version was right and the value was not.
    * Attaching either number is the transport's.
    */
  def requiredPayloadStructure(timestamp: UInt64): Either[ForkGateRefusal, Int] =
    structureAt(EngineForkGate.PayloadStructures, timestamp)

  /** Which `PayloadAttributesVN` the specification requires of a build request
    * with this timestamp.
    *
    * A ladder of its own with the same rungs: over the upgrades this build
    * models, the attributes gained a version at every upgrade the payload did,
    * so the two answer alike. **They are still two ladders**, because that
    * coincidence is a fact about this range and not about the structures. The
    * first upgrade past it already breaks the coincidence: Bogota defines a
    * `PayloadAttributesV5` and no new payload structure at all, its
    * `engine_newPayloadV6` still taking the `ExecutionPayloadV4` the upgrade
    * before it defined (`ethereum/execution-apis` @ `6570b55`
    * `src/engine/bogota.md:44,84`).
    */
  def requiredAttributesStructure(timestamp: UInt64): Either[ForkGateRefusal, Int] =
    structureAt(EngineForkGate.AttributesStructures, timestamp)

  /** Walks the rungs in order and takes the first that admits the timestamp.
    *
    * ==Only ONE refusal means "try the next rung", and the rest must propagate==
    *
    * The rungs ascend, so a timestamp at or past a rung's upper bound belongs to
    * a later one and the walk continues. **Every other refusal is a fact about
    * the network's schedule rather than about which rung we are on**, and
    * treating it as "not this rung" would walk past a misconfigured bound, run
    * out of rungs, and report the structure as unmodeled — a wrong answer
    * naming the wrong cause, from an input that should have been refused
    * outright.
    */
  private def structureAt(
      ladder: Vector[(Int, ForkWindow)],
      timestamp: UInt64
  ): Either[ForkGateRefusal, Int] =
    @annotation.tailrec
    def walk(remaining: Vector[(Int, ForkWindow)]): Either[ForkGateRefusal, Int] =
      remaining.headOption match
        case None                    => Left(ForkGateRefusal.StructureNotModeled(EngineForkGate.LastModeled, timestamp))
        case Some((version, window)) =>
          admits(window, timestamp) match
            case Right(_)                                                 => Right(version)
            case Left(ForkGateRefusal.AtOrAfterFirstUnsupported(_, _, _)) => walk(remaining.tail)
            case Left(refusal)                                            => Left(refusal)
    walk(ladder)

  /** The activation the schedule gives this upgrade, or nothing.
    *
    * Public because deciding whether a version is worth offering at all is a
    * question a caller legitimately asks of a network before any payload
    * arrives, and asking it through [[admits]] would mean inventing a timestamp
    * to ask about.
    */
  def activationOf(upgrade: EngineUpgrade): Option[Activation] =
    schedule.entries
      .find(_.id.label == UpgradeId.Label.Named(upgrade.label))
      .map(_.activation)

  private def activationTimestampOf(upgrade: EngineUpgrade): Either[ForkGateRefusal, UInt64] =
    activationOf(upgrade) match
      case Some(Activation.AtTimestamp(seconds)) => Right(seconds)
      case Some(Activation.Never)                => Left(ForkGateRefusal.UpgradeRefusedByNetwork(upgrade))
      case Some(Activation.Unscheduled)          => Left(ForkGateRefusal.UpgradeNotScheduled(upgrade))
      case Some(other)                           => Left(ForkGateRefusal.UpgradeNotOnTheTimestampAxis(upgrade, other))
      case None                                  => Left(ForkGateRefusal.UpgradeNotScheduled(upgrade))

  /** The same lookup for the upper bound, where an upgrade the network has not
    * dated is NOT a refusal.
    *
    * ==The asymmetry is the whole of this method, and inverting it is silent==
    *
    * A version's lower bound must be known before it can serve anything: with
    * no activation there is nothing to be at or after. Its upper bound is the
    * opposite — an upgrade the network has not scheduled has not superseded
    * anything, so the version goes on serving, which is exactly the state every
    * network is in with respect to the next upgrade it has not yet dated.
    *
    * Treating an undated upper bound as a refusal would take every newest
    * version out of service the moment a later upgrade was named in this
    * module, before any network had dated it. Treating an undated LOWER bound
    * as permission would do the reverse and serve payloads under rules the
    * network has not adopted.
    *
    * An upgrade on the wrong axis still refuses here, because that is a
    * misconfiguration rather than a state of the schedule.
    */
  private def supersedingTimestampOf(upgrade: EngineUpgrade): Either[ForkGateRefusal, Option[UInt64]] =
    activationOf(upgrade) match
      case Some(Activation.AtTimestamp(seconds)) => Right(Some(seconds))
      case Some(Activation.Never)                => Right(None)
      case Some(Activation.Unscheduled)          => Right(None)
      case Some(other)                           => Left(ForkGateRefusal.UpgradeNotOnTheTimestampAxis(upgrade, other))
      case None                                  => Right(None)

object EngineForkGate:

  /** The last upgrade whose structures this build models.
    *
    * Beyond it the payload gains a block access list and a slot number and the
    * attributes gain two fields, and neither link is built — see the closing
    * note on [[PayloadBlobGas]]. Naming it here keeps the ladders below and the
    * refusal they produce agreeing about where the modeling stops.
    */
  val LastModeled: EngineUpgrade = EngineUpgrade.Amsterdam

  /** Which `ExecutionPayloadVN` each span of a network's history requires.
    *
    * Ascending, and read by [[EngineForkGate.requiredPayloadStructure]] as the
    * first rung admitting the timestamp. The rungs are the structure
    * definitions at `ethereum/execution-apis` @ `6570b55`:
    * `src/engine/paris.md:41`, `shanghai.md:54`, `cancun.md:41`, and
    * `amsterdam.md:52` for the rung this build stops before.
    */
  val PayloadStructures: Vector[(Int, ForkWindow)] = Vector(
    1 -> ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai),
    2 -> ForkWindow.from(EngineUpgrade.Shanghai, EngineUpgrade.Cancun),
    3 -> ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Amsterdam)
  )

  /** Which `PayloadAttributesVN` each span requires.
    *
    * The rungs are at `src/engine/paris.md:70`, `shanghai.md:79` and
    * `cancun.md:80`, with `amsterdam.md:84` the one this build stops before.
    */
  val AttributesStructures: Vector[(Int, ForkWindow)] = Vector(
    1 -> ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai),
    2 -> ForkWindow.from(EngineUpgrade.Shanghai, EngineUpgrade.Cancun),
    3 -> ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Amsterdam)
  )
