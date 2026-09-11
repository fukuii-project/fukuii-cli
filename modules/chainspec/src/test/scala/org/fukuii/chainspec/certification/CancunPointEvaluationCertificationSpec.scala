package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Bytes
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}
import org.fukuii.evm.{Halt, Precompile, PrecompileSet}

/** What the two point-evaluation directories decide, measured rather than
  * counted.
  *
  * ==A directory named for a precompile does not establish that it verifies
  * anything==
  *
  * `CertificationCorporaSpec` records why a count is not a finding:
  * a case filled for a fork can pass under a build that never ran the rule the
  * directory is named after. These 180 cases are the first in this repository
  * whose subject is an ARITHMETIC rather than a schedule or a charge, so the
  * question is sharper here than anywhere else -- **does the corpus separate a
  * build that verifies a KZG proof from one that merely answers at `0x0a`?**
  *
  * The two figures below answer it, and they are different questions:
  *
  *   - **Withdrawn** removes the entry, so `0x0a` holds no code. This is the
  *     state this repository was in before the native landed, and the figure
  *     says how many cases notice an address that answers nothing.
  *   - **Stubbed** leaves an entry at `0x0a`, priced identically, that succeeds
  *     with empty output for every input. Nothing about it verifies a proof.
  *     **This is the silent failure `org.fukuii.evm.PrecompileSet.at`'s own
  *     note warns about**, and it is the figure that matters: a corpus that
  *     cannot tell it from the real native is a corpus that certifies the
  *     ADDRESS and not the arithmetic.
  *
  * ==The answer, measured: 152 of the 180 separate the two==
  *
  * Withdrawing moves all 180 and establishes only that the address is reached.
  * Stubbing moves **148 of the evaluating directory's 156** and **4 of the
  * charging directory's 24**. So the corpus does certify the arithmetic, and it
  * does so almost entirely through one of the two directories -- the one named
  * for gas is mostly satisfied by any native at the right price, which is the
  * finding a census of 180 cases would have hidden.
  *
  * ==Why a stub is the right instrument and a wrong ANSWER is not==
  *
  * Perturbing the returned bytes would also move cases, and it would prove
  * less: it establishes that the corpus reads the output, not that it reaches
  * the verification. A stub that succeeds for every input is exactly the build
  * a reader would fear -- one that implements the precompile's SHAPE and none
  * of its content -- so the cases it moves are the cases the arithmetic is
  * actually load-bearing for.
  */
class CancunPointEvaluationCertificationSpec extends AnyFlatSpec:

  private val Evaluating: String = CertificationCorpora.GeneratedCancunPointEvaluationCorpus

  private val Charging: String = CertificationCorpora.GeneratedCancunPointEvaluationGasCorpus

  private val corpora: Vector[String] = Vector(Evaluating, Charging)

  /** Files and cases, stated as literals so a corpus that shrank is a failure
    * rather than a smaller pass.
    */
  private val Census: Map[String, (Int, Int)] =
    Map(Evaluating -> (5, 156), Charging -> (1, 24))

  private val reports: Vector[CorpusReport] =
    CertificationCorpora.reports.getOrElse(
      fail(
        "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
          FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
          " before the sbt server this task runs in was started. A run that cannot find it has measured nothing."
      )
    )

  private def report(corpus: String): CorpusReport =
    reports.find(_.corpus == corpus).getOrElse(fail("the harness assembles no corpus called " + corpus))

  /** Which cases answer differently under an altered run, by name.
    *
    * The pairing is checked before it is relied on, for the reason
    * [[CertificationCorporaSpec]] gives: `zip` truncates to the
    * shorter side rather than complaining, so a rerun yielding fewer outcomes
    * would report a LOW count -- which reads as a corpus that decides less
    * rather than as a rerun that went wrong.
    */
  private def measure(corpus: String, change: UpgradeRules => UpgradeRules): Vector[String] =
    val asIs = report(corpus).outcomes
    val altered = CertificationCorpora
      .rerun(corpus, change)
      .getOrElse(fail("assembled once and not the second time: " + corpus))
      .outcomes
    if asIs.map(_.name) != altered.map(_.name) then
      fail(
        "the rerun of " + corpus + " did not answer for the same cases in the same order: " +
          asIs.length.toString + " outcomes first and " + altered.length.toString + " on the rerun"
      )
    else asIs.zip(altered).collect { case (before, after) if before != after => before.name }

  /** The address holds nothing, which is where this build stood before the
    * native was placed.
    */
  private val withdrawn: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm = rules.evm.copy(precompiles = rules.evm.precompiles.removing(PrecompileSet.PointEvaluation)))

  /** A native at the right address, at the right price, that verifies nothing.
    *
    * Succeeds with empty output for every input -- including the malformed
    * ones, so it does not even enforce the width. A case that still agrees
    * under this is a case that never depended on the arithmetic.
    */
  final private case class AlwaysSucceeds(gas: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = gas
    def run(input: Bytes): Either[Halt, Bytes] = Right(Bytes.Empty)

  private val stubbed: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(precompiles =
          rules.evm.precompiles
            .adding(PrecompileSet.PointEvaluation, AlwaysSucceeds(rules.evm.schedule.precompilePointEvaluation))
        )
      )

  private lazy val movedWithdrawn: Map[String, Vector[String]] =
    corpora.map(corpus => corpus -> measure(corpus, withdrawn)).toMap

  private lazy val movedStubbed: Map[String, Vector[String]] =
    corpora.map(corpus => corpus -> measure(corpus, stubbed)).toMap

  // ── The census, so a corpus that shrank fails rather than passing smaller ──

  "the point-evaluation directories" should "hold the files and cases recorded for them" in
    assert(
      corpora.forall(corpus => (report(corpus).filesRead, report(corpus).casesFound) == Census(corpus)),
      corpora.map(corpus => corpus + " -> " + (report(corpus).filesRead, report(corpus).casesFound).toString).mkString
    )

  it should "agree with every case they ran" in
    assert(
      corpora.forall(corpus => report(corpus).diverged.isEmpty),
      corpora.map(corpus => corpus + " diverged on " + report(corpus).diverged.map(_.name).toString).mkString
    )

  it should "skip nothing" in
    assert(
      corpora.forall(corpus => report(corpus).skipped.isEmpty),
      "a skipped case is one the harness could not read, and would not be evidence either way"
    )

  // ── What the corpus can and cannot see ────────────────────────────────────

  /** The coarse question, and the one a reader assumes is the whole of it.
    *
    * Every case in both directories reaches `0x0a`, so withdrawing the entry
    * moves all 180. **That figure establishes only that the address is
    * reached** -- it is satisfied by any build that answers there at all, which
    * is exactly why the figure below is the one worth having.
    */
  it should "move every case when the address holds nothing" in
    assert(
      corpora.forall(corpus => movedWithdrawn(corpus).length == Census(corpus)._2),
      corpora.map(corpus => corpus + ": " + movedWithdrawn(corpus).length.toString).mkString(", ")
    )

  /** The question the coarse one hides, and the reason this spec exists.
    *
    * ==The two directories answer it very differently, and only one of them
    * certifies the arithmetic==
    *
    * **148 of the evaluating directory's 156 cases** move under a native that
    * answers without verifying, and **4 of the charging directory's 24**. So
    * the first directory is substantially about the proof and the second is
    * substantially not: a stub priced identically costs what the real native
    * costs, and 20 of those 24 cases cannot tell the two apart because the
    * charge is all they were filled to check.
    *
    * **Read the 20 as the directory's subject rather than as a gap in it.** It
    * is named for gas and it certifies gas; the finding is that its name does
    * not make it evidence about the verification, which is the reading a census
    * of 180 cases invites.
    *
    * **The 8 in the evaluating directory are the residual worth knowing about
    * and are NOT characterized here.** A case can agree under a stub for
    * reasons that have nothing to do with the arithmetic -- exhausting its
    * allowance before the answer is observed, or discarding the returned bytes
    * -- and which of those applies to each is a separate reading this spec does
    * not perform. What is asserted is the count, so a build that quietly lost
    * the verification would move fewer and fail here.
    */
  it should "move all but eight of the evaluating cases when the native answers without verifying" in
    assert(
      movedStubbed(Evaluating).length == 148,
      "a case that agrees under a stub verifying nothing is not evidence about the arithmetic; moved " +
        movedStubbed(Evaluating).length.toString + " of " + Census(Evaluating)._2.toString
    )

  it should "move only four of the charging cases, the charge being what that directory checks" in
    assert(
      movedStubbed(Charging).length == 4,
      "a stub priced identically costs what the native costs, so this directory is mostly blind to the " +
        "arithmetic; moved " + movedStubbed(Charging).length.toString + " of " + Census(Charging)._2.toString
    )
