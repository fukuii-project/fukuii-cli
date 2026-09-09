package org.fukuii.consensus.pos

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The version↔fork matrix, driven one upgrade at a time against a schedule
  * that carries the whole activated ladder.
  *
  * ==The rows name a SET, and the first row is why==
  *
  * Before the upgrade that introduced the second version of each verb, **both
  * the first and the second serve**, because there was no moment at which only
  * the first existed. `besu-eth/besu` @ `b330564a94` says so in a comment and
  * builds it that way: *"special case at the first hardfork (Shanghai), before
  * it was possible to call either V1 or V2 so both versions are scheduled at
  * the beginning, and only V1 must be stopped at Shanghai timestamp"*
  * (`ethereum/api/.../ExecutionEngineJsonRpcMethods.java:176-178`), expressed
  * as `thenAlsoFromBeginning` rather than `thenFrom` (`:180`, `:190`, `:204`).
  *
  * So the property every row satisfies is that the serving set is exactly what
  * the row names, and a separate property states the sharper invariant that
  * holds everywhere after that first upgrade: exactly one version serves.
  *
  * ==Where the rows come from==
  *
  * Two independent sources that agree.
  *
  * The specification, one document at a time at `ethereum/execution-apis` @
  * `6570b55` — see the note on [[EngineVersion]], which also records the one
  * bound its wording leaves ambiguous.
  *
  * And besu's own wiring at the ref above, which states each verb's whole
  * ladder as data at `:179-182`, `:189-193` and `:203-208`.
  *
  * A third source covers two of the three columns and cannot cover the last:
  * the published engine fixtures at `tests-v20.0.1` carry `newPayloadVersion`
  * and `forkchoiceUpdatedVersion` per payload and no `getPayload` version at
  * all. Measured over 52,695 entries, the pairs run (1,1) under `for_paris`,
  * (2,2) under `for_shanghai`, (3,3) under `for_cancun`, and (4,3) under
  * `for_prague`, `for_osaka` and every blob-parameter-only label — so the
  * blob-parameter-only rows below are measured for two verbs and reasoned for
  * the third.
  */
class EngineVersionPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val S = PosFixtures.Schedules
  private val gate = EngineForkGate(S.withAmsterdam)
  private val onTheActivatedLadder = EngineForkGate(S.activated)

  private def newPayloadServing(g: EngineForkGate, at: Long): Seq[NewPayloadVersion] =
    NewPayloadVersion.values.toSeq.filter(v => g.admits(v.window, S.at(at)).isRight)

  private def forkchoiceServing(g: EngineForkGate, at: Long): Seq[ForkchoiceUpdatedVersion] =
    ForkchoiceUpdatedVersion.values.toSeq.filter(v => g.admits(v.window, S.at(at)).isRight)

  private def getPayloadServing(g: EngineForkGate, at: Long): Seq[GetPayloadVersion] =
    GetPayloadVersion.values.toSeq.filter(v => g.admits(v.window, S.at(at)).isRight)

  private val newPayloadMatrix = Table(
    ("which upgrade the timestamp falls in", "at", "the versions that serve it"),
    ("before Shanghai", S.shanghai - 1, Seq(NewPayloadVersion.V1, NewPayloadVersion.V2)),
    ("Shanghai", S.shanghai, Seq(NewPayloadVersion.V2)),
    ("Cancun", S.cancun, Seq(NewPayloadVersion.V3)),
    ("Prague", S.prague, Seq(NewPayloadVersion.V4)),
    ("Osaka", S.osaka, Seq(NewPayloadVersion.V4)),
    ("Amsterdam", S.amsterdam, Seq(NewPayloadVersion.V5))
  )

  private val forkchoiceMatrix = Table(
    ("which upgrade the timestamp falls in", "at", "the versions that serve it"),
    ("before Shanghai", S.shanghai - 1, Seq(ForkchoiceUpdatedVersion.V1, ForkchoiceUpdatedVersion.V2)),
    ("Shanghai", S.shanghai, Seq(ForkchoiceUpdatedVersion.V2)),
    ("Cancun", S.cancun, Seq(ForkchoiceUpdatedVersion.V3)),
    ("Prague", S.prague, Seq(ForkchoiceUpdatedVersion.V3)),
    ("Osaka", S.osaka, Seq(ForkchoiceUpdatedVersion.V3)),
    ("Amsterdam", S.amsterdam, Seq(ForkchoiceUpdatedVersion.V4))
  )

  private val getPayloadMatrix = Table(
    ("which upgrade the timestamp falls in", "at", "the versions that serve it"),
    ("before Shanghai", S.shanghai - 1, Seq(GetPayloadVersion.V1, GetPayloadVersion.V2)),
    ("Shanghai", S.shanghai, Seq(GetPayloadVersion.V2)),
    ("Cancun", S.cancun, Seq(GetPayloadVersion.V3)),
    ("Prague", S.prague, Seq(GetPayloadVersion.V4)),
    ("Osaka", S.osaka, Seq(GetPayloadVersion.V5)),
    ("Amsterdam", S.amsterdam, Seq(GetPayloadVersion.V6))
  )

  /** The two blob-parameter-only upgrades, which no window names and which every
    * verb must go on serving from the version that covered the upgrade before
    * them.
    */
  private val blobParameterOnly = Table(
    ("which upgrade the timestamp falls in", "at"),
    ("BPO1", S.bpo1),
    ("BPO2", S.bpo2)
  )

  private val afterTheFirstUpgrade = Table(
    ("which upgrade the timestamp falls in", "at"),
    ("Shanghai", S.shanghai),
    ("Cancun", S.cancun),
    ("Prague", S.prague),
    ("Osaka", S.osaka),
    ("Amsterdam", S.amsterdam)
  )

  property("the newPayload versions serving each upgrade are exactly the ones the matrix names") {
    forAll(newPayloadMatrix) { (what: String, at: Long, expected: Seq[NewPayloadVersion]) =>
      assert(newPayloadServing(gate, at) == expected, what + " is served by a different set of newPayload versions")
    }
  }

  property("the forkchoiceUpdated versions serving each upgrade are exactly the ones the matrix names") {
    forAll(forkchoiceMatrix) { (what: String, at: Long, expected: Seq[ForkchoiceUpdatedVersion]) =>
      assert(forkchoiceServing(gate, at) == expected, what + " is served by a different set of fcU versions")
    }
  }

  property("the getPayload versions serving each upgrade are exactly the ones the matrix names") {
    forAll(getPayloadMatrix) { (what: String, at: Long, expected: Seq[GetPayloadVersion]) =>
      assert(getPayloadServing(gate, at) == expected, what + " is served by a different set of getPayload versions")
    }
  }

  property("from the first upgrade onward exactly one version of each verb serves") {
    forAll(afterTheFirstUpgrade) { (what: String, at: Long) =>
      assert(
        newPayloadServing(gate, at).size == 1 &&
          forkchoiceServing(gate, at).size == 1 &&
          getPayloadServing(gate, at).size == 1,
        what + " has more than one version of some verb serving it, which only the pre-Shanghai span may"
      )
    }
  }

  property("a blob-parameter-only upgrade moves no verb's version") {
    forAll(blobParameterOnly) { (what: String, at: Long) =>
      assert(
        newPayloadServing(onTheActivatedLadder, at) == newPayloadServing(onTheActivatedLadder, S.osaka) &&
          forkchoiceServing(onTheActivatedLadder, at) == forkchoiceServing(onTheActivatedLadder, S.osaka) &&
          getPayloadServing(onTheActivatedLadder, at) == getPayloadServing(onTheActivatedLadder, S.osaka),
        what + " moved a version away from the upgrade before it, which no blob-parameter-only upgrade does"
      )
    }
  }

  property("newPayload and forkchoiceUpdated are on separate axes from Prague onward") {
    val divergent = Table(("which upgrade the timestamp falls in", "at"), ("Prague", S.prague), ("Osaka", S.osaka))
    forAll(divergent) { (what: String, at: Long) =>
      assert(
        newPayloadServing(gate, at).map(_.ordinal) != forkchoiceServing(gate, at).map(_.ordinal),
        what + " has the two verbs on the same version, which would make one axis of two"
      )
    }
  }

  property("getPayload is on a third axis, moving at an upgrade that moves neither of the others") {
    assert(
      getPayloadServing(gate, S.osaka) != getPayloadServing(gate, S.prague) &&
        newPayloadServing(gate, S.osaka) == newPayloadServing(gate, S.prague) &&
        forkchoiceServing(gate, S.osaka) == forkchoiceServing(gate, S.prague),
      "Osaka moved getPayload alone, and a design with two axes would have missed it"
    )
  }
