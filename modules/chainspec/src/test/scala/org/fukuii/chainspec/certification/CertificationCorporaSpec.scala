package org.fukuii.chainspec.certification

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereum}
import org.fukuii.chainspec.{Activation, DifficultyAdjustment, Network, Registry, UpgradeRules}
import org.fukuii.evm.fixtures.*
import org.fukuii.evm.{Opcode, PrecompileSet}

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** How much of a corpus was there, and how much of it ran.
  *
  * These figures are a record of the corpus at the refs the manifest names, not
  * a target. They are asserted so that a corpus which has moved, or a reader
  * which has begun dropping cases, fails rather than reporting a smaller run as
  * a clean one.
  */
final case class CorpusCensus(files: Int, cases: Int, skipped: Int)

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
  * the console, and any check built on that text is coupled to how ScalaTest
  * chooses to print. An assertion is coupled to nothing.
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
class CertificationCorporaSpec extends AnyPropSpec with TableDrivenPropertyChecks:

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
    // The same 2394 files a third time. 579 of them carry a section under this
    // key and it expands to 1221 runnable combinations, more than either fork
    // above -- a general state test states expectations for every fork it was
    // authored against, and the later the fork the more of the corpus has one.
    CertificationCorpora.LegacyEip158StateCorpus -> CorpusCensus(files = 2394, cases = 3036, skipped = 1815),
    CertificationCorpora.GeneratedTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0),
    CertificationCorpora.GeneratedSpuriousDragonCorpus -> CorpusCensus(files = 34, cases = 537, skipped = 0),
    // The same 2394 files a fourth time, and the fork at which nearly all of
    // them answer: 2297 carry a section under this key and it expands to 4899
    // runnable combinations, against 579 files and 1221 combinations one fork
    // earlier. The 97 skipped are the files that state nothing here at all.
    CertificationCorpora.LegacyByzantiumStateCorpus -> CorpusCensus(files = 2394, cases = 4899, skipped = 97),
    // Twice the files of any earlier generated directory and more than three
    // times the cases. 25 of the 70 sit under this fork's own name, in five
    // directories each named for one of its nine proposals, which is a shape no
    // earlier directory of this tier has.
    CertificationCorpora.GeneratedByzantiumCorpus -> CorpusCensus(files = 70, cases = 1845, skipped = 0),
    // The same 33 files as the row above, resolved through the other network's
    // schedule at that network's own activation. The figures are identical
    // because the corpus is: what differs is which schedule was asked, and at
    // what height.
    CertificationCorpora.ClassicTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0)
  )

  /** Every censused corpus, as the rows the four properties below drive.
    *
    * ==Built FROM the census, so a corpus cannot be censused without being
    * asserted==
    *
    * Written out per corpus instead, a corpus could be added to the census AND
    * to what the harness assembles and simply never be asserted about: it would
    * run, its divergences would be discarded, and no count would move, because
    * the number of TESTS would be unchanged. Rows derived from the census have
    * no such step to forget.
    *
    * Three things below close the set between them. A corpus assembled and not
    * censused fails the property that compares the two names; a corpus censused
    * and not assembled fails these rows; and a corpus dropped from BOTH leaves
    * those two agreeing with each other, so a third counts the census instead.
    */
  private val censused = Table(("corpus", "expected"), census.toSeq.sortBy(_._1)*)

  private val registry: Registry =
    KnownNetworks.registry.getOrElse(fail("the authored networks do not form a registry"))

  /** Every network-and-height pair the harness resolves rules at. */
  private val resolutions = Table(("network", "height"), CertificationCorpora.resolutionPoints*)

  /** The assembled reports, or a canceled test where there is no corpus.
    *
    * **Called before `forAll` and never inside it.** `TableForN.forAll` catches
    * `Throwable` in order to attach the failing row, which turns the exception a
    * cancellation is carried by into a failure. Raised per row, the absence of a
    * corpus would therefore report as every property FAILING rather than as
    * every case cancelling -- and a build with no corpus would be
    * indistinguishable from a broken machine.
    */
  private def assembled: Vector[CorpusReport] =
    CertificationCorpora.reports.getOrElse(
      cancel(
        "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
          FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
          " before the sbt server this task runs in was started"
      )
    )

  private def found(reports: Vector[CorpusReport], corpus: String): CorpusReport =
    reports.find(_.corpus == corpus).getOrElse(fail("censused but never assembled: " + corpus))

  property("the fixture corpus is configured, or nothing below this line certifies anything") {
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
  }

  property("every corpus the harness assembles is censused") {
    // One half of the pair. A corpus the harness assembles but never censuses
    // would run with nothing asking it anything, and no count would move,
    // because the number of TESTS would be unchanged. The other half is the
    // table above, which derives its rows from the census so that the reverse --
    // censused and never assembled -- cannot happen either.
    val names = assembled.map(_.corpus).toSet
    assert(names == census.keySet, s"assembled ${names.toString} against a census of ${census.keySet.toString}")
  }

  property("the census covers eleven corpora, counted") {
    // THE REMOVAL CASE, which the pairing cannot see. Dropping a corpus from the
    // census AND from what the harness assembles leaves those two agreeing with
    // each other, leaves the same six properties registered, and leaves the
    // expected total unmoved -- so a tier can be deleted with every signal green.
    // Deriving the rows from the census closed the addition case and left this
    // one open in the same shape.
    //
    // It matters because of when it happens: deleting a row is the move
    // available to whoever needs a red build green after an upstream corpus
    // moves, which is exactly the moment a ratchet earns its keep.
    //
    // Eleven: the interpreter tier, the state tier read for Frontier, again for
    // EIP-150, again for EIP-158 and again for Byzantium, the generated state
    // tier filled for Frontier, Homestead, Tangerine Whistle, Spurious Dragon
    // and Byzantium, and Tangerine Whistle read a second time through the other
    // network's schedule. Raising this is adding a corpus. Lowering it is
    // dropping certified cases, and that is a decision rather than a tidy-up.
    assert(census.size == 11, s"the census covers ${census.size.toString} corpora rather than eleven")
  }

  property("every censused corpus holds the files the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.filesRead == expected.files, report.describe)
    }
  }

  property("every censused corpus holds the cases the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.casesFound == expected.cases, report.describe)
    }
  }

  property("every censused corpus skips exactly the cases the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.skipped.length == expected.skipped, report.describe)
    }
  }

  property("every censused corpus agrees with every case it ran") {
    val reports = assembled
    forAll(censused) { (corpus: String, _: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.diverged.isEmpty, report.describe)
    }
  }

  property("one corpus run through both networks' schedules reaches the same verdict on every case") {
    // The strongest thing two networks in one build can say to each other. Both
    // adopted EIP-150 unaltered and switched it on 37,000 blocks apart, so this
    // pair of reports differs in exactly one input -- which schedule was asked,
    // and at which height -- and must differ in no output.
    //
    // Comparing the verdicts rather than the counts is deliberate: two runs can
    // agree on how many cases diverged while diverging on different ones.
    val reports = assembled
    val throughEthereum = found(reports, CertificationCorpora.GeneratedTangerineWhistleCorpus)
    val throughClassic = found(reports, CertificationCorpora.ClassicTangerineWhistleCorpus)
    assert(
      throughEthereum.outcomes == throughClassic.outcomes,
      throughEthereum.describe + " || " + throughClassic.describe
    )
  }

  /** The fork's clearing clause switched off, leaving everything else in force. */
  private val withoutClearing: UpgradeRules => UpgradeRules =
    rules => rules.copy(execution = rules.execution.copy(touchedEmptyAccountsAreDeleted = false))

  /** The fork's bound on deployed code lifted, leaving everything else in force. */
  private val withoutTheCodeBound: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(maxCodeSize = None))

  /** Which cases of a corpus answer differently once one rule is removed from
    * the fork, which is that corpus's coverage OF that rule.
    *
    * Names rather than a count, because for a rule reached by very few cases the
    * identity is the durable fact and the count is not: a corpus that lost its
    * one case and gained an unrelated one reports the same number.
    *
    * Compared per case rather than by the two divergence totals, for the same
    * reason -- a change that repaired one case and broke another would leave
    * those totals equal while changing what the corpus said.
    *
    * ==The pairing is positional, so it is checked before it is relied on==
    *
    * `zip` truncates to the shorter side rather than complaining, so a rerun
    * yielding fewer outcomes would leave the tail of the first run compared
    * against nothing and report a LOW count -- which reads as a corpus that
    * decides less, not as a rerun that went wrong. The names are compared as
    * sequences rather than the two lengths, because two runs of the same length
    * over different cases pair each verdict with a stranger's and every
    * mismatch is then counted as a case that moved.
    */
  private def movedBy(
      reports: Vector[CorpusReport],
      corpus: String,
      change: UpgradeRules => UpgradeRules
  ): Vector[String] =
    val asIs = found(reports, corpus).outcomes
    val altered = CertificationCorpora
      .rerun(corpus, change)
      .getOrElse(fail("assembled once and not the second time: " + corpus))
      .outcomes
    if asIs.map(_.name) != altered.map(_.name) then
      fail(
        "the rerun of " + corpus + " did not answer for the same cases in the same order: " +
          asIs.length.toString + " outcomes first and " + altered.length.toString + " on the rerun"
      )
    asIs.zip(altered).collect { case (before, after) if before != after => before.name }

  /** The chain identifier stopped being readable out of a signature. */
  private val withoutChainIdSignatures: UpgradeRules => UpgradeRules =
    rules => rules.copy(admission = rules.admission.copy(signatureMayCarryChainId = false))

  /** Exponentiation back at the price the previous fork charged. */
  private val withoutTheExpReprice: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(schedule = rules.evm.schedule.copy(expPerByte = BigInt(10))))

  /** The revert operation taken out. */
  private val withoutRevert: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.Revert)))

  /** Both operations of the return-data buffer taken out together, because the
    * document adds them together and a machine holding one of them is a state
    * no fork ever shipped.
    */
  private val withoutTheReturnDataBuffer: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(table = rules.evm.table.removing(Opcode.ReturnDataSize).removing(Opcode.ReturnDataCopy))
      )

  /** The static call operation taken out. */
  private val withoutStaticCall: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.StaticCall)))

  /** The native answering at the modular-exponentiation address taken out, so
    * that address is an ordinary account again.
    */
  private val withoutModularExponentiation: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(precompiles = rules.evm.precompiles.removing(PrecompileSet.ModExp)))

  /** Both natives of the curve-arithmetic document taken out together: one
    * document places two addresses, and removing one of them is a machine no
    * fork ever shipped.
    */
  private val withoutCurveArithmetic: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(precompiles =
          rules.evm.precompiles.removing(PrecompileSet.AltBn128Add).removing(PrecompileSet.AltBn128Mul)
        )
      )

  /** The native answering at the pairing-check address taken out. */
  private val withoutThePairingCheck: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm = rules.evm.copy(precompiles = rules.evm.precompiles.removing(PrecompileSet.AltBn128PairingCheck)))

  /** A receipt back to carrying a state root in its first field. */
  private val withoutTheStatusByte: UpgradeRules => UpgradeRules =
    rules => rules.copy(execution = rules.execution.copy(receiptCarriesStatus = false))

  /** Difficulty targeted the way the fork before this one targeted it. */
  private val withoutTheOmmerAwareAdjustment: UpgradeRules => UpgradeRules =
    rules => rules.copy(consensus = rules.consensus.copy(difficultyAdjustment = DifficultyAdjustment.Eip2))

  /** Both halves of the reward document reverted -- the amount back to what the
    * chain launched with, and the exponential term back to being measured from
    * the block being settled.
    */
  private val withoutTheRewardReduction: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(consensus =
        rules.consensus.copy(blockReward = ethereum.Upgrades.launchReward, difficultyBombDelay = BigInt(0))
      )

  /** How many cases each censused tier decides on each proposal of the two
    * forks either tier is read at, measured by removing the proposal and
    * rerunning.
    *
    * ==What a corpus MENTIONS and what it can DECIDE are different claims==
    *
    * A tier filled for a fork is routinely read as certifying that fork, and
    * this matrix is what that reading would get wrong. Counting fields in the
    * files cannot produce it either: for the clearing clause the JSON gives the
    * wrong answer with conviction, because the state the clause removes is
    * absent from a correct post state exactly as it is absent from a corpus that
    * never tested it.
    *
    * **Counting FILE NAMES gets it wrong in the other direction, and the later
    * fork is where that shows.** No file of the generated tier at that fork has
    * the revert operation anywhere in its path, and 45 files of the older tier
    * are named for it -- so a census by name gives the generated tier nothing.
    * The differential gives it 337 cases against the older tier's 56, because
    * it reaches the operation through cases filed under later proposals
    * entirely: a net-gas-metering suite whose call targets revert, and an
    * every-opcode case. A corpus reaches a rule wherever its cases happen to
    * exercise it, which no reading of a directory listing recovers.
    *
    * ==Neither tier certifies either fork, and the two are near-complements==
    *
    * At the earlier fork the generated tier is the only one that reaches
    * EIP-155 and the older tier is the only one that reaches EIP-170, so
    * dropping either leaves a proposal decided by nothing at all. At the later
    * fork the same holds for the receipt's status byte, which only the
    * generated tier publishes -- and the older tier decides three of that
    * fork's proposals by a wider margin than the generated one does. That is
    * why both are censused at both.
    *
    * ==Two proposals of the later fork are reached by NEITHER tier, and that is
    * a gap rather than a certification==
    *
    * A state fixture settles one transaction against a block it is handed, so
    * nothing it can express reads what a block's producer is credited or how
    * the next block's difficulty is targeted. Both zeros are therefore
    * structural rather than a shortfall in either corpus: no state tier at any
    * fork can move them. What pins the two is elsewhere -- the difficulty rule
    * against the published difficulty vectors the mechanism's own module
    * certifies at this fork, and the reward against its own unit coverage.
    *
    * ==Every row is another row's control==
    *
    * A zero here is a claim that a corpus cannot see a rule, and on its own it
    * is indistinguishable from a rerun that ignored its own argument. Each zero
    * sits beside a non-zero produced by the same machinery over the same corpus,
    * so the machinery is shown working at the moment the zero is read.
    *
    * Every zero is explainable rather than mysterious, which is the other thing
    * that makes them safe to assert. EIP-155 is unreachable in the older tier
    * because that corpus publishes no signed bytes for any case, so no signature
    * is ever recovered and nothing ever asks which chain it names; EIP-658 is
    * unreachable there because that tier publishes no receipt for any case at
    * any fork, so there is nothing for a status byte to differ in.
    */
  private val coverageRows: Vector[(String, String, UpgradeRules => UpgradeRules, Int)] =
    Vector(
      ("EIP-155", CertificationCorpora.GeneratedSpuriousDragonCorpus, withoutChainIdSignatures, 500),
      ("EIP-155", CertificationCorpora.LegacyEip158StateCorpus, withoutChainIdSignatures, 0),
      ("EIP-160", CertificationCorpora.GeneratedSpuriousDragonCorpus, withoutTheExpReprice, 1),
      ("EIP-160", CertificationCorpora.LegacyEip158StateCorpus, withoutTheExpReprice, 47),
      ("EIP-161", CertificationCorpora.GeneratedSpuriousDragonCorpus, withoutClearing, 48),
      ("EIP-161", CertificationCorpora.LegacyEip158StateCorpus, withoutClearing, 74),
      ("EIP-170", CertificationCorpora.GeneratedSpuriousDragonCorpus, withoutTheCodeBound, 0),
      ("EIP-170", CertificationCorpora.LegacyEip158StateCorpus, withoutTheCodeBound, 1),
      ("EIP-100", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheOmmerAwareAdjustment, 0),
      ("EIP-100", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheOmmerAwareAdjustment, 0),
      ("EIP-140", CertificationCorpora.GeneratedByzantiumCorpus, withoutRevert, 337),
      ("EIP-140", CertificationCorpora.LegacyByzantiumStateCorpus, withoutRevert, 56),
      ("EIP-196", CertificationCorpora.GeneratedByzantiumCorpus, withoutCurveArithmetic, 184),
      ("EIP-196", CertificationCorpora.LegacyByzantiumStateCorpus, withoutCurveArithmetic, 989),
      ("EIP-197", CertificationCorpora.GeneratedByzantiumCorpus, withoutThePairingCheck, 199),
      ("EIP-197", CertificationCorpora.LegacyByzantiumStateCorpus, withoutThePairingCheck, 141),
      ("EIP-198", CertificationCorpora.GeneratedByzantiumCorpus, withoutModularExponentiation, 44),
      ("EIP-198", CertificationCorpora.LegacyByzantiumStateCorpus, withoutModularExponentiation, 340),
      ("EIP-211", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheReturnDataBuffer, 410),
      ("EIP-211", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheReturnDataBuffer, 47),
      ("EIP-214", CertificationCorpora.GeneratedByzantiumCorpus, withoutStaticCall, 267),
      ("EIP-214", CertificationCorpora.LegacyByzantiumStateCorpus, withoutStaticCall, 433),
      ("EIP-649", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheRewardReduction, 0),
      ("EIP-649", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheRewardReduction, 0),
      ("EIP-658", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheStatusByte, 1834),
      ("EIP-658", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheStatusByte, 0)
    )

  /** Every row above rerun, once each, keyed by the proposal and the corpus.
    *
    * ==A rerun is a whole pass over a corpus, and two properties want the same
    * row==
    *
    * [[CertificationCorpora.rerun]] rebuilds the harness and runs every case
    * again, and the older tier here is 2394 files that the baseline already
    * reads four times. Computing per call meant the row naming the case below
    * was run twice for one answer, so this holds each row's result and both
    * readers take it from here.
    *
    * ==This is where the certification run spends its time==
    *
    * One row is one pass over one corpus, and a pass costs about eleven
    * milliseconds a case whichever tier it is over -- so the matrix is the
    * dominant cost of the whole suite, and it grows with the product of the
    * proposals asserted and the tiers they are asserted against rather than
    * with the number of tests. **A row removed to make the suite quicker is a
    * proposal nothing measures the corpora against**, which is the trade to
    * refuse rather than the saving to take.
    *
    * ==Forced outside `forAll`, like [[assembled]] and for its reason==
    *
    * The initializer cancels where there is no corpus, and a cancellation
    * raised inside a table's handler is reported as a failure. So each property
    * below reads this before entering `forAll`, never inside one.
    */
  private lazy val movedPerRow: Map[(String, String), Vector[String]] =
    val reports = assembled
    coverageRows.map { case (proposal, corpus, without, _) =>
      (proposal, corpus) -> movedBy(reports, corpus, without)
    }.toMap

  /** The rows as the matrix asserts them. The rule removed is absent because
    * [[movedPerRow]] has already applied it, and a function renders as nothing
    * a reader can use in a failing row anyway.
    */
  private val coverage =
    Table(
      ("proposal", "corpus", "cases decided"),
      coverageRows.map { case (proposal, corpus, _, decided) => (proposal, corpus, decided) }*
    )

  property("each tier decides the cases the coverage matrix records") {
    val moved = movedPerRow
    forAll(coverage) { (proposal: String, corpus: String, decided: Int) =>
      val names = moved((proposal, corpus))
      assert(
        names.length == decided,
        s"$corpus decides ${names.length.toString} cases on $proposal rather than ${decided.toString}"
      )
    }
  }

  property("the one case the coverage matrix records for the bound on deployed code is the one named") {
    // The count above would still read as coverage if this case were dropped and
    // an unrelated one began to move, which for a rule reached by exactly one
    // case is the whole of the risk. Naming it is what closes that.
    //
    // Recorded as a coverage fact and not as a complaint: what pins EIP-170 is
    // its own unit coverage, and a reader who takes a certified fork to be one
    // whose every proposal the published corpora exercise would be wrong here.
    val moved = movedPerRow(("EIP-170", CertificationCorpora.LegacyEip158StateCorpus))
    assert(
      moved == Vector("codesizeOOGInvalidSize[d0g0v0]"),
      s"the bound decides these cases: ${moved.mkString(", ")}"
    )
  }

  property("no corpus is resolved through a height that is not an activation on its network") {
    // What stops the heights above being quietly slid to somewhere convenient
    // after a divergence. Each one must be a point the network actually forks
    // at, which the schedule states and the harness does not.
    forAll(resolutions) { (network: Network, height: Long) =>
      val schedule = registry.at(network.chainId).getOrElse(fail("no schedule for " + network.name))
      assert(
        height == 0L || schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(height))),
        network.name + " is asked for its rules at block " + height.toString +
          ", which is not an activation on its schedule: " + schedule.forkPoints.toString
      )
    }
  }
