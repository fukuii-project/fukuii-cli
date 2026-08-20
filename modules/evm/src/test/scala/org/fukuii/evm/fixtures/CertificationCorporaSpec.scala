package org.fukuii.evm.fixtures

import org.scalatest.flatspec.AnyFlatSpec

/** How much of a corpus was there, and how much of it ran.
  *
  * These figures are a record of the corpus at the refs the manifest names, not
  * a target. They are asserted so that a corpus which has moved, or a reader
  * which has begun dropping cases, fails rather than reporting a smaller run as
  * a clean one.
  */
final case class CorpusCensus(files: Int, cases: Int, skipped: Int):
  def executed: Int = cases - skipped

/** The certification run: every published fixture this layer can reach, at every
  * fork it has rules for, against the machine.
  *
  * ==A missing corpus FAILS here, and the individual cases still cancel==
  *
  * The corpora are third-party artifacts of tens of megabytes and are assembled
  * beside a clone rather than inside it. Without them there is nothing to
  * measure, and a case that passed in that state would report conformance it
  * never checked -- so each one cancels, naming the variable that supplies the
  * corpus. `FixtureCalibrationSpec` is what still runs, and what shows the
  * harness would notice a divergence if it saw one.
  *
  * **Cancelling is the right answer for a case and the wrong answer for the
  * run.** A canceled test appears in no total ScalaTest reports, so a build with
  * no corpus certified nothing while sbt, the executed count and every exit code
  * agreed it had passed. The first case below is what makes that state loud: it
  * asserts the corpus is configured at all, so it FAILS rather than cancelling,
  * and one failing test is a signal every layer above already understands.
  *
  * It is checked here rather than in the shell because the shell can only read
  * the console. Any check built on that text is coupled to how ScalaTest chooses
  * to print, and one written against today's layout stopped firing when a reason
  * wrapped onto a second line -- while still printing its all-clear.
  *
  * ==Named for what it does rather than for a fork==
  *
  * It began as one fork's certification and is now several, which is why neither
  * this suite nor the object it drives carries a fork's name any more. A shared
  * thing named for one network's release of it invites the next reader to treat
  * a per-fork fact as a general one, and the cost of the rename rises with every
  * fork added. The fork names live on the individual corpora, where each one
  * correctly labels the expectations that corpus is read for.
  */
class CertificationCorporaSpec extends AnyFlatSpec:

  private val census: Map[String, CorpusCensus] = Map(
    CertificationCorpora.LegacyVmCorpus -> CorpusCensus(files = 609, cases = 609, skipped = 0),
    CertificationCorpora.LegacyFrontierStateCorpus -> CorpusCensus(files = 2394, cases = 2691, skipped = 1668),
    CertificationCorpora.GeneratedStateCorpus -> CorpusCensus(files = 31, cases = 530, skipped = 0),
    CertificationCorpora.GeneratedHomesteadCorpus -> CorpusCensus(files = 34, cases = 545, skipped = 0),
    // The same 2394 files as the Frontier row, asked a different question. Every
    // figure differs: a case counts as found when it states an expectation at
    // the fork asked about OR states none, and a case that states one expands
    // into a run per post entry -- so 650 cases carrying this key become 1096.
    CertificationCorpora.LegacyEip150StateCorpus -> CorpusCensus(files = 2394, cases = 2840, skipped = 1744),
    CertificationCorpora.GeneratedTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0)
  )

  "the fixture corpus" should "be configured, or nothing below this line certifies anything" in
    // The one case here that does not cancel when the corpus is absent, and the
    // whole of what makes that state visible. Everything after it measures the
    // machine; this measures whether there was anything to measure it against.
    assert(
      FixtureCorpus.root.isDefined,
      "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
        FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
        " before the sbt server this task runs in was started. Every case below will cancel, and a" +
        " canceled case is counted by nothing -- so without this one the run would certify nothing" +
        " and report success."
    )

  private def report(corpus: String): CorpusReport =
    CertificationCorpora
      .reportFor(corpus)
      .getOrElse(
        cancel(
          "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
            FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
            " before the sbt server this task runs in was started"
        )
      )

  private def expected(corpus: String): CorpusCensus =
    census.getOrElse(corpus, throw new IllegalStateException("no census recorded for " + corpus))

  CertificationCorpora.LegacyVmCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.LegacyVmCorpus)
    assert(found.filesRead == expected(CertificationCorpora.LegacyVmCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.LegacyVmCorpus)
    assert(found.casesFound == expected(CertificationCorpora.LegacyVmCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.LegacyVmCorpus)
    assert(found.skipped.length == expected(CertificationCorpora.LegacyVmCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.LegacyVmCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  CertificationCorpora.LegacyFrontierStateCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.LegacyFrontierStateCorpus)
    assert(found.filesRead == expected(CertificationCorpora.LegacyFrontierStateCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.LegacyFrontierStateCorpus)
    assert(found.casesFound == expected(CertificationCorpora.LegacyFrontierStateCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.LegacyFrontierStateCorpus)
    assert(found.skipped.length == expected(CertificationCorpora.LegacyFrontierStateCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.LegacyFrontierStateCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  CertificationCorpora.GeneratedStateCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.GeneratedStateCorpus)
    assert(found.filesRead == expected(CertificationCorpora.GeneratedStateCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedStateCorpus)
    assert(found.casesFound == expected(CertificationCorpora.GeneratedStateCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedStateCorpus)
    assert(found.skipped.length == expected(CertificationCorpora.GeneratedStateCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.GeneratedStateCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  CertificationCorpora.GeneratedHomesteadCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.GeneratedHomesteadCorpus)
    assert(found.filesRead == expected(CertificationCorpora.GeneratedHomesteadCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedHomesteadCorpus)
    assert(found.casesFound == expected(CertificationCorpora.GeneratedHomesteadCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedHomesteadCorpus)
    assert(found.skipped.length == expected(CertificationCorpora.GeneratedHomesteadCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.GeneratedHomesteadCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  CertificationCorpora.LegacyEip150StateCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.LegacyEip150StateCorpus)
    assert(found.filesRead == expected(CertificationCorpora.LegacyEip150StateCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.LegacyEip150StateCorpus)
    assert(found.casesFound == expected(CertificationCorpora.LegacyEip150StateCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.LegacyEip150StateCorpus)
    assert(found.skipped.length == expected(CertificationCorpora.LegacyEip150StateCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.LegacyEip150StateCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  CertificationCorpora.GeneratedTangerineWhistleCorpus should "hold the files the census records" in {
    val found = report(CertificationCorpora.GeneratedTangerineWhistleCorpus)
    assert(found.filesRead == expected(CertificationCorpora.GeneratedTangerineWhistleCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedTangerineWhistleCorpus)
    assert(found.casesFound == expected(CertificationCorpora.GeneratedTangerineWhistleCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(CertificationCorpora.GeneratedTangerineWhistleCorpus)
    assert(
      found.skipped.length == expected(CertificationCorpora.GeneratedTangerineWhistleCorpus).skipped,
      found.describe
    )
  }

  it should "agree with every case it ran" in {
    val found = report(CertificationCorpora.GeneratedTangerineWhistleCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }
