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

/** The certification run: every published Frontier fixture this layer can
  * reach, against the machine.
  *
  * ==Cancelled rather than passed when the corpus is absent==
  *
  * The corpora are third-party artifacts of tens of megabytes and are assembled
  * beside a clone rather than inside it. Without them there is nothing to
  * measure, and a test that passed in that state would report conformance it
  * never checked -- so each one cancels, naming the variable that supplies the
  * corpus. `FixtureCalibrationSpec` is what still runs, and what shows the
  * harness would notice a divergence if it saw one.
  */
class FrontierCorpusSpec extends AnyFlatSpec:

  private val census: Map[String, CorpusCensus] = Map(
    FrontierCorpus.LegacyVmCorpus -> CorpusCensus(files = 609, cases = 609, skipped = 0),
    FrontierCorpus.LegacyStateCorpus -> CorpusCensus(files = 2394, cases = 2691, skipped = 1668),
    FrontierCorpus.GeneratedStateCorpus -> CorpusCensus(files = 31, cases = 530, skipped = 0),
    FrontierCorpus.GeneratedHomesteadCorpus -> CorpusCensus(files = 34, cases = 545, skipped = 0)
  )

  private def report(corpus: String): CorpusReport =
    FrontierCorpus
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

  FrontierCorpus.LegacyVmCorpus should "hold the files the census records" in {
    val found = report(FrontierCorpus.LegacyVmCorpus)
    assert(found.filesRead == expected(FrontierCorpus.LegacyVmCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(FrontierCorpus.LegacyVmCorpus)
    assert(found.casesFound == expected(FrontierCorpus.LegacyVmCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(FrontierCorpus.LegacyVmCorpus)
    assert(found.skipped.length == expected(FrontierCorpus.LegacyVmCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(FrontierCorpus.LegacyVmCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  FrontierCorpus.LegacyStateCorpus should "hold the files the census records" in {
    val found = report(FrontierCorpus.LegacyStateCorpus)
    assert(found.filesRead == expected(FrontierCorpus.LegacyStateCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(FrontierCorpus.LegacyStateCorpus)
    assert(found.casesFound == expected(FrontierCorpus.LegacyStateCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(FrontierCorpus.LegacyStateCorpus)
    assert(found.skipped.length == expected(FrontierCorpus.LegacyStateCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(FrontierCorpus.LegacyStateCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  FrontierCorpus.GeneratedStateCorpus should "hold the files the census records" in {
    val found = report(FrontierCorpus.GeneratedStateCorpus)
    assert(found.filesRead == expected(FrontierCorpus.GeneratedStateCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(FrontierCorpus.GeneratedStateCorpus)
    assert(found.casesFound == expected(FrontierCorpus.GeneratedStateCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(FrontierCorpus.GeneratedStateCorpus)
    assert(found.skipped.length == expected(FrontierCorpus.GeneratedStateCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(FrontierCorpus.GeneratedStateCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }

  FrontierCorpus.GeneratedHomesteadCorpus should "hold the files the census records" in {
    val found = report(FrontierCorpus.GeneratedHomesteadCorpus)
    assert(found.filesRead == expected(FrontierCorpus.GeneratedHomesteadCorpus).files, found.describe)
  }

  it should "hold the cases the census records" in {
    val found = report(FrontierCorpus.GeneratedHomesteadCorpus)
    assert(found.casesFound == expected(FrontierCorpus.GeneratedHomesteadCorpus).cases, found.describe)
  }

  it should "skip exactly the cases the census records" in {
    val found = report(FrontierCorpus.GeneratedHomesteadCorpus)
    assert(found.skipped.length == expected(FrontierCorpus.GeneratedHomesteadCorpus).skipped, found.describe)
  }

  it should "agree with every case it ran" in {
    val found = report(FrontierCorpus.GeneratedHomesteadCorpus)
    assert(found.diverged.isEmpty, found.describe)
  }
