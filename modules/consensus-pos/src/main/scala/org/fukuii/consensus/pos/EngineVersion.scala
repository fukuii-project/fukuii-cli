package org.fukuii.consensus.pos

/** The version↔fork matrix, as three tables that cannot be conflated.
  *
  * ==Three axes, and one of them is invisible in the published corpus==
  *
  * The three verbs move version on their own schedules and a single "engine
  * version for this fork" is wrong from Prague onward. Read down any column of
  * the three enums below and the divergence is on the face of it: at Prague
  * `newPayload` moves to V4 while `forkchoiceUpdated` stays at V3, and at Osaka
  * `getPayload` moves to V5 while both of the others stand still.
  *
  * **Two axes are measurable from the published fixtures and the third is
  * not.** `blockchain_tests_engine` at `tests-v20.0.1` publishes
  * `newPayloadVersion` and `forkchoiceUpdatedVersion` per payload and publishes
  * no `getPayload` version at all — **67,025 payloads across 7,862 files**, the
  * two published keys agreeing exactly on that figure and `getPayloadVersion`
  * returning zero. The pairs are (1,1) at Paris, (2,2) at Shanghai, (3,3) at
  * Cancun, and **(4,3) at Prague, Osaka and every blob-parameter-only label** —
  * so a design derived from that corpus alone would find two axes and ship a
  * third defect it could not see.
  *
  * **The zero is a measurement rather than an absence of one**: the same sweep
  * returns 121,463 for a key that must be there and zero for one that cannot be,
  * so it discriminates. **This figure read 52,695 until it was re-measured**,
  * which reproduced nothing — no directory, key or label under that release
  * gives it — while `.claude/protocols/consensus-engine-api.md` had 67,025 and
  * was right.
  *
  * ==The versions are published as STRINGS, not as numbers==
  *
  * `"newPayloadVersion": "4"`, quoted, in all 67,025. A reader expecting a JSON
  * number finds nothing and reports a corpus that states no version — which is
  * the shape of a clean zero that means the instrument was wrong.
  *
  * ==Modeling them as three enums rather than one integer is the guard==
  *
  * A single `version: Int` threaded through three verbs is transposable at
  * every call site and compiles. Three types are not. This is the same argument
  * [[org.fukuii.chainspec.Activation]] makes for keeping the block and
  * timestamp axes apart rather than collapsing them into one number.
  */
/** How many arguments each `engine_newPayload` version takes, beside the window
  * it serves.
  *
  * Read off the parameter lists rather than derived from the version number, and
  * the two do not track each other: `ethereum/execution-apis` @ `6570b5500`
  * gives V1 and V2 one argument (`paris.md:148`, `shanghai.md:90`), V3 three
  * (`cancun.md:92`), V4 four (`prague.md:27`) and **V5 four again**
  * (`amsterdam.md:103`) -- so a version that adds no argument still exists, and
  * arity is a fact per version rather than a count of upgrades.
  *
  * **A call whose arguments do not match is `-32602: Invalid params`**, which
  * this module answers as a fact rather than as a number: the wire code belongs
  * to a transport that does not exist here yet.
  */
enum NewPayloadVersion(val window: ForkWindow, val arguments: Int):

  case V1 extends NewPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai), 1)
  case V2 extends NewPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Cancun), 1)
  case V3 extends NewPayloadVersion(ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Prague), 3)
  case V4 extends NewPayloadVersion(ForkWindow.from(EngineUpgrade.Prague, EngineUpgrade.Amsterdam), 4)
  case V5 extends NewPayloadVersion(ForkWindow.fromOnward(EngineUpgrade.Amsterdam), 4)

enum ForkchoiceUpdatedVersion(val window: ForkWindow):

  case V1 extends ForkchoiceUpdatedVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai))
  case V2 extends ForkchoiceUpdatedVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Cancun))
  case V3 extends ForkchoiceUpdatedVersion(ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Amsterdam))
  case V4 extends ForkchoiceUpdatedVersion(ForkWindow.fromOnward(EngineUpgrade.Amsterdam))

enum GetPayloadVersion(val window: ForkWindow):

  case V1 extends GetPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai))
  case V2 extends GetPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Cancun))
  case V3 extends GetPayloadVersion(ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Prague))
  case V4 extends GetPayloadVersion(ForkWindow.from(EngineUpgrade.Prague, EngineUpgrade.Osaka))
  case V5 extends GetPayloadVersion(ForkWindow.from(EngineUpgrade.Osaka, EngineUpgrade.Amsterdam))
  case V6 extends GetPayloadVersion(ForkWindow.fromOnward(EngineUpgrade.Amsterdam))

/** Where the three tables above come from, and the one place the specification
  * alone would have left them ambiguous.
  *
  * ==The specification states the bounds one document at a time==
  *
  * Each fork's document adds a `-38005: Unsupported fork` **MUST** to the verb
  * it moves and, in an *"Update the methods of previous forks"* section, to the
  * version it supersedes. Read together at `ethereum/execution-apis` @
  * `6570b55` those give: Cancun bounding all three V2s
  * (`src/engine/cancun.md:235`), Prague bounding `newPayloadV3` and
  * `getPayloadV3` and extending `forkchoiceUpdatedV3` to cover *"the Cancun
  * **or Prague** forks"* (`prague.md:96,99`), Osaka bounding `getPayloadV4`
  * alone (`osaka.md:174`), and Amsterdam bounding what is left
  * (`amsterdam.md:288`).
  *
  * ==One bound the specification's own wording gets wrong, read literally==
  *
  * `prague.md:49` requires `newPayloadV4` to refuse a payload whose timestamp
  * *"does not fall within the time frame of the Prague fork"*, which read
  * literally would refuse every Osaka payload. It cannot mean that: Osaka's
  * document supersedes `getPayloadV4` and says nothing about `newPayloadV4`, so
  * nothing takes over serving Osaka payloads, and the published fixtures under
  * `for_osaka` carry `newPayloadVersion` 4. **The operative upper bound is the
  * next upgrade that actually moves the verb**, which is Amsterdam.
  *
  * `besu-eth/besu` @ `b330564a94` resolves it the same way and is the second
  * source these tables rest on. Its wiring states each verb's whole ladder as
  * data, and `VersionScheduler.thenFrom` closes the previous version's window
  * at the upgrade that opens the next one (`:288-289`,
  * `pendingMethods.forEach(mvbd -> readyMethods.add(mvbd.withTo(hardforkId)))`).
  * The three ladders are at
  * `ethereum/api/.../ExecutionEngineJsonRpcMethods.java:179-182` for
  * `forkchoiceUpdated`, `:189-193` for `newPayload`, and `:203-208` for
  * `getPayload`, and every row above is one line of those.
  *
  * ==Where this build departs from besu, deliberately==
  *
  * besu drops a version from its method registry entirely when the upgrade
  * opening its window is not configured on the network
  * (`ExecutionEngineJsonRpcMethods.java:297-305`, the `build` filter), so an
  * unconfigured version answers *method not found*. Here it stays callable and
  * the gate refuses it with
  * [[ForkGateRefusal.UpgradeNotScheduled]]. **The difference is which layer
  * decides**: dropping a method is a registry decision and this module has no
  * registry, so the refusal is the honest domain answer and mapping it to a
  * wire code is the transport's.
  */
object EngineVersion
