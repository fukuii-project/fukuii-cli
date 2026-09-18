package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.Activation

/** The gate's refusals, and the boundary each one fires on.
  *
  * The version↔fork matrix itself is a table and is asserted in
  * [[EngineVersionPropSpec]]; this suite settles the gate's own behavior — which
  * end of a window is inclusive, what an unresolvable bound does, and which
  * misconfigurations are refused rather than compared.
  */
class EngineForkGateSpec extends AnyFlatSpec:

  private val S = PosFixtures.Schedules
  private val gate = EngineForkGate(S.activated)
  private val cancunWindow = ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Prague)

  private def refusalOf(schedule: org.fukuii.chainspec.UpgradeSchedule, at: Long): Option[ForkGateRefusal] =
    EngineForkGate(schedule).admits(cancunWindow, S.at(at)).left.toOption

  "EngineForkGate.admits" should "accept a timestamp exactly on the first supported activation" in
    assert(
      gate.admits(cancunWindow, S.at(S.cancun)).isRight,
      "the lower bound is inclusive, so the first block of the upgrade is served by the version it opens"
    )

  it should "refuse the last timestamp before the first supported activation" in
    assert(
      gate.admits(cancunWindow, S.at(S.cancun - 1)) ==
        Left(ForkGateRefusal.BeforeFirstSupported(EngineUpgrade.Cancun, S.at(S.cancun), S.at(S.cancun - 1))),
      "one second earlier belongs to the previous version, and the refusal names both figures it compared"
    )

  it should "refuse a timestamp exactly on the first unsupported activation" in
    assert(
      gate.admits(cancunWindow, S.at(S.prague)) ==
        Left(ForkGateRefusal.AtOrAfterFirstUnsupported(EngineUpgrade.Prague, S.at(S.prague), S.at(S.prague))),
      "the upper bound is exclusive, so the superseding upgrade's own first block belongs to the next version"
    )

  it should "accept the last timestamp before the first unsupported activation" in
    assert(
      gate.admits(cancunWindow, S.at(S.prague - 1)).isRight,
      "one second earlier is the last moment this version serves"
    )

  it should "accept every timestamp between the two bounds" in
    assert(
      gate.admits(cancunWindow, S.at((S.cancun + S.prague) / 2)).isRight,
      "a window with both ends resolved admits its interior"
    )

  "EngineForkGate.admits, on a window with no lower bound" should "accept a timestamp before every upgrade" in
    assert(
      gate.admits(NewPayloadVersion.V1.window, S.at(1L)).isRight,
      "a version serving since the Engine API began has no lower bound that can refuse"
    )

  "EngineForkGate.admits, on a window with no upper bound" should "accept a timestamp far past every upgrade" in
    assert(
      EngineForkGate(S.withAmsterdam).admits(NewPayloadVersion.V5.window, S.at(S.amsterdam * 100)).isRight,
      "nothing has superseded the newest version, so its upper half cannot refuse however late the timestamp"
    )

  it should "still refuse where its own opening upgrade is undated" in
    assert(
      gate.admits(NewPayloadVersion.V5.window, S.at(S.amsterdam * 100)) ==
        Left(ForkGateRefusal.UpgradeNotScheduled(EngineUpgrade.Amsterdam)),
      "a version whose opening upgrade a network has not taken serves nothing on it, however late the timestamp"
    )

  "EngineForkGate.admits, where the first supported upgrade is absent from the schedule" should
    "refuse rather than serve" in
    assert(
      refusalOf(S.withoutCancun, S.shanghai + 1) == Some(ForkGateRefusal.UpgradeNotScheduled(EngineUpgrade.Cancun)),
      "with no activation there is nothing to be at or after, so the version cannot serve anything yet"
    )

  it should "distinguish an upgrade the network has not dated from one it never took" in
    assert(
      refusalOf(S.cancunUnscheduled, S.shanghai + 1) ==
        Some(ForkGateRefusal.UpgradeNotScheduled(EngineUpgrade.Cancun)),
      "an undated upgrade may still arrive, which is a different fact from a refused one"
    )

  it should "report a permanently declined upgrade as declined" in
    assert(
      refusalOf(S.cancunRefused, S.shanghai + 1) ==
        Some(ForkGateRefusal.UpgradeRefusedByNetwork(EngineUpgrade.Cancun)),
      "a reader told only unscheduled would wait for an activation that is not coming"
    )

  "EngineForkGate.admits, where a bound sits on the block-number axis" should "refuse rather than compare" in
    assert(
      refusalOf(S.cancunOnTheBlockAxis, 999999L) ==
        Some(ForkGateRefusal.UpgradeNotOnTheTimestampAxis(EngineUpgrade.Cancun, Activation.AtBlock(S.at(12L)))),
      "a block height compared against a timestamp answers confidently and admits everything"
    )

  "EngineForkGate.admits, where the superseding upgrade is undated" should "keep serving" in
    assert(
      EngineForkGate(S.activated).admits(NewPayloadVersion.V4.window, S.at(S.osaka)).isRight,
      "an upgrade nothing has dated has superseded nothing, which is every network's state at its newest version"
    )

  "EngineForkGate.admits, where the superseding upgrade is dated" should "stop serving at it" in
    assert(
      EngineForkGate(S.withAmsterdam).admits(NewPayloadVersion.V4.window, S.at(S.amsterdam)).isLeft,
      "dating the upgrade is what closes the window, and nothing else about the version changed"
    )

  "EngineForkGate.activationOf" should "answer for an upgrade the schedule carries" in
    assert(
      gate.activationOf(EngineUpgrade.Cancun).contains(Activation.AtTimestamp(S.at(S.cancun))),
      "a caller deciding whether to offer a version at all asks this before any payload exists"
    )

  it should "answer nothing for an upgrade the schedule does not carry" in
    assert(
      EngineForkGate(S.withoutCancun).activationOf(EngineUpgrade.Cancun).isEmpty,
      "the label is matched exactly, so an upgrade under another name is absent rather than adjacent"
    )

  "EngineForkGate, against the mainnet schedule this build carries" should
    "serve the earliest version up to the upgrade that supersedes it" in
    assert(
      EngineForkGate(S.ethereumMainnet)
        .admits(NewPayloadVersion.V1.window, S.at(S.mainnetShanghai - 1))
        .isRight,
      "Shanghai's activation is history and cannot move, so this bound is stable authored data"
    )

  it should "stop serving that version at the upgrade itself" in
    assert(
      EngineForkGate(S.ethereumMainnet)
        .admits(NewPayloadVersion.V1.window, S.at(S.mainnetShanghai))
        .isLeft,
      "the activation is read out of the schedule rather than copied here, so it cannot drift from it"
    )

  // ── How many arguments a version takes, against what a call carried ────────

  /** A call carrying `n` of the four arguments the newest version takes. */
  private def carrying(arguments: Int): NewPayloadRequest =
    val payload = PosFixtures.withBlobGas
    arguments match
      case 1 => NewPayloadRequest(payload)
      case 3 => NewPayloadRequest(payload, Some(BlobAndBeaconArguments(Seq.empty, PosFixtures.hash(0xbe))))
      case _ =>
        NewPayloadRequest(
          payload,
          Some(
            BlobAndBeaconArguments(
              Seq.empty,
              PosFixtures.hash(0xbe),
              Some(ExecutionRequestsArgument(Seq(PosFixtures.requestBytes)))
            )
          )
        )

  "a call whose arguments match its version" should "be admitted at each of the three arities" in
    assert(
      gate.admitsArguments(NewPayloadVersion.V1, carrying(1)).isRight &&
        gate.admitsArguments(NewPayloadVersion.V3, carrying(3)).isRight &&
        gate.admitsArguments(NewPayloadVersion.V4, carrying(4)).isRight,
      "the payload alone, the pair added with it, and the request list after that"
    )

  "a call dropping the request list" should "be refused by the version that takes one" in
    // The gap the window cannot reach. A version invoked outside its range is
    // already refused by `admits`; this is a call at the RIGHT version carrying
    // the wrong arguments, which nothing about a timestamp comparison sees.
    // Left unchecked, the header would commit to no requests and the answer
    // would come back as a block-hash mismatch, naming a defect that is not
    // there.
    assert(
      gate.admitsArguments(NewPayloadVersion.V4, carrying(3)) ==
        Left(ForkGateRefusal.WrongArgumentCount(NewPayloadVersion.V4, 4, 3)),
      "what the version takes and what the call carried are both reported"
    )

  "a call carrying a request list" should "be refused by the version that takes none" in
    assert(
      gate.admitsArguments(NewPayloadVersion.V3, carrying(4)) ==
        Left(ForkGateRefusal.WrongArgumentCount(NewPayloadVersion.V3, 3, 4)),
      "the mismatch is refused in both directions, not only the one that loses an argument"
    )

  "the arity of a version" should "not be derivable from its number" in
    // Why this is a value per version rather than a count. V5 adds a version
    // and no argument, so a gate computing arity from the version number would
    // demand a fifth argument that no method takes.
    assert(
      NewPayloadVersion.V5.arguments == NewPayloadVersion.V4.arguments &&
        NewPayloadVersion.V1.arguments == NewPayloadVersion.V2.arguments,
      "two pairs of versions share an arity, at opposite ends of the ladder"
    )

  it should "be independent of whether the window admits the payload" in
    // The two gates ask different questions of the same call. This one carries
    // the right arguments for V1 and a timestamp V1 no longer serves, so it
    // passes here and fails there -- which is what makes both checks necessary.
    assert(
      gate.admitsArguments(NewPayloadVersion.V1, carrying(1)).isRight &&
        EngineForkGate(S.ethereumMainnet).admits(NewPayloadVersion.V1.window, S.at(S.mainnetShanghai)).isLeft,
      "arity and window are independent facts about one call"
    )
