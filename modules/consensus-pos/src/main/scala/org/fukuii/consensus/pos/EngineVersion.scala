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
  * no `getPayload` version at all — measured over 52,695 entries, where the two
  * it does publish run (1,1), (2,2), (3,3) and then (4,3) for Prague, Osaka and
  * every blob-parameter-only label. So a design derived from that corpus alone
  * would find two axes and ship a third defect it could not see.
  *
  * ==Modeling them as three enums rather than one integer is the guard==
  *
  * A single `version: Int` threaded through three verbs is transposable at
  * every call site and compiles. Three types are not. This is the same argument
  * [[org.fukuii.chainspec.Activation]] makes for keeping the block and
  * timestamp axes apart rather than collapsing them into one number.
  */
enum NewPayloadVersion(val window: ForkWindow):

  case V1 extends NewPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Shanghai))
  case V2 extends NewPayloadVersion(ForkWindow.fromTheBeginning(EngineUpgrade.Cancun))
  case V3 extends NewPayloadVersion(ForkWindow.from(EngineUpgrade.Cancun, EngineUpgrade.Prague))
  case V4 extends NewPayloadVersion(ForkWindow.from(EngineUpgrade.Prague, EngineUpgrade.Amsterdam))
  case V5 extends NewPayloadVersion(ForkWindow.fromOnward(EngineUpgrade.Amsterdam))

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
