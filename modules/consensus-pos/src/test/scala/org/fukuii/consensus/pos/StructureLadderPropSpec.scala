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

  /** Both ladders, so a property about how a ladder is SHAPED runs over each.
    *
    * Above the first registration deliberately: a table `val` below one is read
    * during construction by an already-registered body, which Scala 3's
    * initialization checker rejects with a diagnostic naming the first test in
    * the class rather than the field.
    */
  private val ladders = Table(
    ("which ladder", "rungs"),
    ("payload structures", EngineForkGate.PayloadStructures),
    ("attributes structures", EngineForkGate.AttributesStructures)
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
        Left(ForkGateRefusal.StructureNotModeled(EngineForkGate.FirstUnmodeled, S.at(S.amsterdam))),
      "letting the newest modeled structure stand in for one it is not would accept a payload missing fields"
    )
  }

  property("a schedule that never took an upgrade holds the structure at the last one it did") {
    assert(
      EngineForkGate(S.withoutCancun).requiredPayloadStructure(S.at(S.cancun)) == Right(2),
      "an upgrade a network did not take supersedes nothing on it, so the previous structure goes on applying"
    )
  }

  /** ==Two arrangements, because a refusal is only one of the two ways the walk
    * can go wrong==
    *
    * The ladder continues past exactly one refusal — the timestamp being at or
    * after a rung's upper bound — and must propagate every other. A guard that
    * continued past all of them fails differently depending on which rung
    * carries the defect, and only one of the two shapes is a refusal at all.
    *
    * **So neither of these asserts `isLeft`.** This one carries the defect at
    * the ladder's top, where a wrong walk exhausts the rungs and refuses with
    * the wrong cause — so the refusal's identity is the whole assertion, and
    * `isLeft` would hold under the defect it exists to catch. The next carries
    * it at the bottom, where a wrong walk answers.
    */
  property("a bound on the wrong axis is refused for that reason rather than walked past") {
    assert(
      EngineForkGate(S.cancunOnTheBlockAxis).requiredPayloadStructure(S.at(999999L)) ==
        Left(ForkGateRefusal.UpgradeNotOnTheTimestampAxis(EngineUpgrade.Cancun, S.wrongAxisActivation)),
      "walking past a misconfigured bound would run out of rungs and blame the modeling for a schedule defect"
    )
  }

  property("a wrong-axis bound below a dated rung is refused rather than answered") {
    assert(
      EngineForkGate(S.shanghaiOnTheBlockAxis).requiredPayloadStructure(S.at(S.cancun + 500L)) ==
        Left(ForkGateRefusal.UpgradeNotOnTheTimestampAxis(EngineUpgrade.Shanghai, S.wrongAxisActivation)),
      "every rung is bounded by Shanghai or by something above it, so walking past this defect reaches a rung " +
        "that admits and returns a structure resolved over a schedule no version can serve"
    )
  }

  /** ==The refusal's constant and the ladder's top rung are the same fact==
    *
    * [[EngineForkGate.FirstUnmodeled]] is what an exhausted walk reports, and
    * the top rung's own `firstUnsupported` is what exhausts the walk. Nothing
    * in the types makes them agree, so a rung added without moving the constant
    * would refuse a timestamp past the NEW top while naming the OLD one — a
    * refusal that is correct about there being no structure and wrong about
    * where the modeling stops.
    *
    * Asserting the coupling is cheaper than removing it. Deriving the constant
    * from the ladder would need an answer for a ladder with no rungs, which is
    * a state no ladder is in and no test could reach.
    */
  property("the upgrade the unmodeled refusal names is the top rung's own upper bound") {
    forAll(ladders) { (which: String, rungs: Vector[(Int, ForkWindow)]) =>
      assert(
        rungs.lastOption.flatMap(_._2.firstUnsupported).contains(EngineForkGate.FirstUnmodeled),
        which + " tops out somewhere other than " + EngineForkGate.FirstUnmodeled.toString +
          ", so a timestamp past it would be refused naming an upgrade that is not where the modeling stops"
      )
    }
  }
