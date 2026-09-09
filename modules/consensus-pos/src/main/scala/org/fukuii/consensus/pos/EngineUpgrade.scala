package org.fukuii.consensus.pos

/** An upgrade that moves an engine method from one version to the next.
  *
  * ==Why this is a small set, and why no blob-parameter-only upgrade is in it==
  *
  * A network's schedule holds every upgrade it ever took. Only a few of them
  * ever change which engine method version serves a payload, and those are the
  * only ones a version window has to name. **A blob-parameter-only upgrade
  * never does** — it moves the blob schedule and nothing the Engine API's own
  * documents key a version on — so BPO1 through BPO5 are deliberately absent
  * here and are still served correctly, because the window that covers the fork
  * before them covers them too.
  *
  * That is the whole payoff of expressing the map as an interval rather than as
  * a set of forks: a set would have to be edited for every blob-parameter-only
  * upgrade that ships, and an omission would refuse live traffic. An interval
  * needs no edit and cannot omit one.
  *
  * ==The names are the specification's, not a network's==
  *
  * `ethereum/execution-apis` @ `6570b55` keys its own documents on these words
  * — `src/engine/shanghai.md`, `cancun.md`, `prague.md`, `osaka.md`,
  * `amsterdam.md` — and a version's window is a fact about the Engine API
  * rather than about any one network. What varies per network is when the
  * upgrade activates, which is [[org.fukuii.chainspec.UpgradeSchedule]]'s
  * answer and not this type's.
  *
  * `besu-eth/besu` @ `b330564a94` draws the same line: its engine methods name
  * `MainnetHardforkId` constants and ask the schedule
  * `milestoneFor(hardforkId)` for the timestamp
  * (`ethereum/core/.../ProtocolSchedule.java:70`).
  *
  * ==Paris is absent, and its absence is the "from the beginning" case==
  *
  * The Engine API begins at Paris, so the earliest versions have no lower bound
  * to name — see [[ForkWindow.fromTheBeginning]]. Naming Paris here would also
  * put a **block-activated** upgrade into a set every member of which is
  * compared against a timestamp; on Ethereum mainnet Paris activates at a block
  * number, so that comparison would be across axes and wrong.
  */
enum EngineUpgrade(val label: String):

  case Shanghai extends EngineUpgrade("Shanghai")
  case Cancun extends EngineUpgrade("Cancun")
  case Prague extends EngineUpgrade("Prague")
  case Osaka extends EngineUpgrade("Osaka")
  case Amsterdam extends EngineUpgrade("Amsterdam")

/** The span of a network's history one engine method version serves.
  *
  * ==Half-open, and both ends are optional==
  *
  * `[firstSupported, firstUnsupported)`. A version serves a payload whose
  * timestamp is at or after the first bound and strictly before the second.
  *
  * The shape is `besu-eth/besu` @ `b330564a94`'s, which is the only surveyed
  * client that states the map as data rather than as branches:
  * `ExecutionEngineJsonRpcMethod.java:185-186` passes exactly this pair into
  * `ForkSupportHelper.validateForkSupported`, whose two comparisons are `<` on
  * the lower bound and `>=` on the upper (`ForkSupportHelper.java:32,61`).
  *
  * ==Both ends are absent for real reasons, not as a default==
  *
  * An absent `firstSupported` means the version has served since the Engine API
  * began — the earliest two versions of every verb do, because before the
  * upgrade that introduced the second one there was no way to call either.
  * besu records that in a comment at
  * `ExecutionEngineJsonRpcMethods.java:176-178`, and expresses it by adding the
  * second version with `thenAlsoFromBeginning` rather than `thenFrom`
  * (`:180`, `:190`, `:204`).
  *
  * An absent `firstUnsupported` means nothing has yet superseded the version.
  * It is the newest version of its verb, and the upgrade that will bound it has
  * not been specified.
  */
final case class ForkWindow(firstSupported: Option[EngineUpgrade], firstUnsupported: Option[EngineUpgrade])

object ForkWindow:

  /** Served since the Engine API began, until the given upgrade. */
  def fromTheBeginning(until: EngineUpgrade): ForkWindow = ForkWindow(None, Some(until))

  /** Served from the given upgrade until the next one to move this verb. */
  def from(first: EngineUpgrade, until: EngineUpgrade): ForkWindow = ForkWindow(Some(first), Some(until))

  /** Served from the given upgrade onward, nothing having superseded it yet. */
  def fromOnward(first: EngineUpgrade): ForkWindow = ForkWindow(Some(first), None)
