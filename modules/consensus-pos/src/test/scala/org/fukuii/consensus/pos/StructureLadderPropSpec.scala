package org.fukuii.consensus.pos

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Which structure the specification requires at each point of a network's
  * history, and the one thing that ladder must never do.
  *
  * ==The check this makes possible is the `-32602` one==
  *
  * A payload's own [[ExecutionPayload.structureVersion]] and the ladder's answer
  * are two independently derived numbers — one walked off the value's appended
  * chain, one resolved out of the schedule — and `engine_newPayload` MUST refuse
  * where they differ (`ethereum/execution-apis` @ `6570b55`
  * `src/engine/shanghai.md:99`). Neither number is any use without the other,
  * which is why the payload's chain and this ladder are worth their cost
  * together and neither is alone.
  */
class StructureLadderPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val S = PosFixtures.Schedules
  private val gate = EngineForkGate(S.withAmsterdam)
  private val onTheActivatedLadder = EngineForkGate(S.activated)

  private val required = Table(
    ("which upgrade the timestamp falls in", "at", "payload", "attributes"),
    ("before Shanghai", S.shanghai - 1, 1, 1),
    ("Shanghai", S.shanghai, 2, 2),
    ("Cancun", S.cancun, 3, 3),
    ("Prague", S.prague, 3, 3),
    ("Osaka", S.osaka, 3, 3),
    ("BPO1", S.bpo1, 3, 3),
    ("BPO2", S.bpo2, 3, 3)
  )

  property("the payload structure required at each upgrade is the one the specification defines") {
    forAll(required) { (what: String, at: Long, payload: Int, _: Int) =>
      assert(
        onTheActivatedLadder.requiredPayloadStructure(S.at(at)) == Right(payload),
        what + " requires a payload structure the specification does not define for it"
      )
    }
  }

  property("the attributes structure required at each upgrade is the one the specification defines") {
    forAll(required) { (what: String, at: Long, _: Int, attributes: Int) =>
      assert(
        onTheActivatedLadder.requiredAttributesStructure(S.at(at)) == Right(attributes),
        what + " requires an attributes structure the specification does not define for it"
      )
    }
  }

  property("the required structure never moves at an upgrade that only changes the blob schedule") {
    val acrossTheBoundary = Table(
      ("which pair straddles a blob-parameter-only boundary", "before", "after"),
      ("Osaka into BPO1", S.osaka, S.bpo1),
      ("BPO1 into BPO2", S.bpo1, S.bpo2)
    )
    forAll(acrossTheBoundary) { (what: String, before: Long, after: Long) =>
      assert(
        onTheActivatedLadder.requiredPayloadStructure(S.at(before)) ==
          onTheActivatedLadder.requiredPayloadStructure(S.at(after)),
        what + " changed the required structure, which no blob-parameter-only upgrade does"
      )
    }
  }

  property("a timestamp past the last modeled upgrade is refused rather than answered") {
    assert(
      gate.requiredPayloadStructure(S.at(S.amsterdam)) ==
        Left(ForkGateRefusal.StructureNotModeled(EngineForkGate.LastModeled, S.at(S.amsterdam))),
      "letting the newest modeled structure stand in for one it is not would accept a payload missing fields"
    )
  }

  property("a schedule that never took an upgrade holds the structure at the last one it did") {
    assert(
      EngineForkGate(S.withoutCancun).requiredPayloadStructure(S.at(S.cancun)) == Right(2),
      "an upgrade a network did not take supersedes nothing on it, so the previous structure goes on applying"
    )
  }

  property("a bound on the wrong axis is refused rather than walked past") {
    assert(
      EngineForkGate(S.cancunOnTheBlockAxis).requiredPayloadStructure(S.at(999999L)).isLeft,
      "walking past a misconfigured bound would run out of rungs and blame the modeling for a schedule defect"
    )
  }
