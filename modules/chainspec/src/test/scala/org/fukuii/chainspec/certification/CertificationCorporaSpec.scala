package org.fukuii.chainspec.certification

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.{KnownNetworks, ethereum}
import org.fukuii.chainspec.proposals.eip.{Eip2565, Eip2718, Eip2929, Eip2930}
import org.fukuii.chainspec.{Activation, Component, DifficultyAdjustment, Network, Registry, UpgradeRules}
import org.fukuii.evm.fixtures.*
import org.fukuii.evm.{Cost, Operation, Precompile, PrecompileSet, StorageMetering, Opcode}

import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor3}
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
    CertificationCorpora.ClassicTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0),
    CertificationCorpora.GeneratedConstantinopleFixCorpus -> CorpusCensus(files = 98, cases = 1963, skipped = 0),
    CertificationCorpora.LegacyConstantinopleFixStateCorpus -> CorpusCensus(files = 2394, cases = 10596, skipped = 27),
    CertificationCorpora.LegacyConstantinopleStateCorpus -> CorpusCensus(files = 2394, cases = 10598, skipped = 21),
    // Every case carries a section under this fork's key, so nothing is skipped
    // for want of an expectation -- the same shape as the generated tier at the
    // two forks below and unlike either legacy row above. The two typed
    // envelopes in the directory are not skipped either: a format this fork
    // does not admit is REFUSED, which is a verdict, and the fixtures expect
    // exactly that refusal.
    CertificationCorpora.GeneratedIstanbulCorpus -> CorpusCensus(files = 104, cases = 2075, skipped = 0),
    // Nothing is skipped here either, and for this tier that is a stronger
    // statement than at the fork below: 298 of its entries carry a typed
    // envelope, 154 of which the rules admit and execute. A typed entry the
    // rules refused would still be a verdict rather than a skip, so a zero here
    // is not evidence that the format is admitted -- the coverage rows are.
    CertificationCorpora.GeneratedBerlinCorpus -> CorpusCensus(files = 132, cases = 2742, skipped = 0)
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

  property("the census covers sixteen corpora, counted") {
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
    // Deliberately a bare count and not an enumeration. A list here restates
    // the census immediately above it, and a second copy of a membership goes
    // stale on the commit that adds a corpus rather than on the one that
    // rewrites it -- which is what happened to the list this replaced, left
    // naming eleven tiers while the assertion beside it counted fourteen.
    //
    // Raising this is adding a corpus. Lowering it is dropping certified cases,
    // and that is a decision rather than a tidy-up.
    assert(census.size == 16, s"the census covers ${census.size.toString} corpora rather than sixteen")
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

  /** All three shifting instructions taken out together, because the document
    * adds them together and a machine holding one of them is a state no fork
    * ever shipped.
    */
  private val withoutTheShifts: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(table = rules.evm.table.removing(Opcode.Shl).removing(Opcode.Shr).removing(Opcode.Sar))
      )

  /** The code-hash operation taken out. */
  private val withoutExtCodeHash: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.ExtCodeHash)))

  /** The salted creation taken out. */
  private val withoutSaltedCreation: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.Create2)))

  /** Storage priced the way every fork below this one priced it.
    *
    * **Not a removal of an operation but of a SCHEME**, which is why it reaches
    * a rules member rather than the table: the operation is priced from its
    * operands and has no entry to take out.
    */
  private val withoutNetMetering: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(storageMetering = StorageMetering.Legacy))

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

  /** The native answering at the compression address taken out, so that address
    * is an ordinary account again.
    */
  private val withoutBlake2fCompression: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(precompiles = rules.evm.precompiles.removing(PrecompileSet.Blake2f)))

  /** All three curve natives back at the prices the fork below charged.
    *
    * **The precompiles are rebuilt and not only the schedule**, because each
    * carries its own price from the moment it is placed and reads the schedule
    * never again. A revision that moved the four figures alone would leave
    * three natives still charging the new prices, and the row would report this
    * corpus blind to a repricing it exercises several hundred times.
    */
  private val withoutTheCurveReprice: UpgradeRules => UpgradeRules =
    rules =>
      val priced = rules.evm.schedule.copy(
        precompileAltBn128Add = BigInt(500),
        precompileAltBn128Mul = BigInt(40000),
        precompileAltBn128PairingBase = BigInt(100000),
        precompileAltBn128PairingPerPoint = BigInt(80000)
      )
      rules.copy(evm =
        rules.evm.copy(
          schedule = priced,
          precompiles = rules.evm.precompiles
            .adding(PrecompileSet.AltBn128Add, Precompile.AltBn128Add(priced.precompileAltBn128Add))
            .adding(PrecompileSet.AltBn128Mul, Precompile.AltBn128Mul(priced.precompileAltBn128Mul))
            .adding(
              PrecompileSet.AltBn128PairingCheck,
              Precompile.AltBn128PairingCheck(
                priced.precompileAltBn128PairingBase,
                priced.precompileAltBn128PairingPerPoint
              )
            )
        )
      )

  /** The operation naming the chain taken out. */
  private val withoutTheChainIdOpcode: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.ChainId)))

  /** The three trie-reading operations back at the prices the fork below
    * charged, and the balance-of-self operation taken out with them.
    *
    * Both halves together, because one document does both and a machine holding
    * one of them is a state no fork ever shipped -- the same reading the
    * return-data and shift rows above already take.
    *
    * **The table is rebuilt for the same reason the curve row rebuilds the
    * precompiles**: an entry copies its cost when it is placed, so moving the
    * schedule alone leaves all three operations charging the new figures.
    */
  private val withoutTheTrieSizeReprice: UpgradeRules => UpgradeRules =
    rules =>
      val priced = rules.evm.schedule.copy(
        storageLoad = BigInt(200),
        balance = BigInt(400),
        extCodeHash = BigInt(400)
      )
      rules.copy(evm =
        rules.evm.copy(
          schedule = priced,
          table = rules.evm.table
            .adding(Operation(Opcode.SLoad, Cost.Fixed(priced.storageLoad)))
            .adding(Operation(Opcode.Balance, Cost.Fixed(priced.balance)))
            .adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(priced.extCodeHash)))
            .removing(Opcode.SelfBalance)
        )
      )

  /** A non-zero calldata byte back at the price the fork below charged.
    *
    * **The schedule alone is the whole of it here**, unlike the two rows above:
    * this figure is read once per transaction by the intrinsic charge and is
    * copied into no table entry and no precompile.
    */
  private val withoutTheCallDataReprice: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm = rules.evm.copy(schedule = rules.evm.schedule.copy(transactionDataPerNonZeroByte = BigInt(68))))

  /** Storage metered and priced the way the fork below metered and priced it.
    *
    * The scheme and the four figures together: the document rebalances the
    * clauses and moves what they charge in one act, and a machine metering by
    * one and charging by the other is a state no fork ever shipped.
    *
    * **`Legacy` and not `Net` is what the fork below ran.** The scheme this
    * reverts to is the one that was in force after EIP-1283 was withdrawn,
    * which is the same target the EIP-1283 row above reverts to from the other
    * side.
    */
  private val withoutTheStorageSentry: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(
          storageMetering = StorageMetering.Legacy,
          schedule = rules.evm.schedule.copy(
            netStorageNoop = BigInt(200),
            netStorageDirty = BigInt(200),
            refundNetStorageResetFromZero = BigInt(19800),
            refundNetStorageReset = BigInt(4800)
          )
        )
      )

  /** The four proposals the highest fork here adopts, in the order its
    * composition takes them.
    */
  private val theBerlinFour: Vector[Component] =
    Vector(Eip2565.component, Eip2718.component, Eip2929.component, Eip2930.component)

  /** The fork below with three of the four adopted.
    *
    * ==Recomposed rather than reverted field by field, unlike every row above==
    *
    * Adoption has no inverse -- a component is an arbitrary function over the
    * whole rule set -- so the rows above each hand-write the figures a proposal
    * moved and rebuild whatever copied them. Two of these four defeat that
    * shape outright: the access repricing moves eight schedule figures, four
    * table entries and one metering rule together, and the declaring format is
    * one member of a different facet. Hand-reverting either is a second
    * statement of the proposal's own delta, maintained beside it and wrong the
    * first time one of them changes.
    *
    * **What recomposition costs is that it can drift from the schedule**, which
    * the hand-written rows cannot: they start from whatever the schedule
    * resolved. A property below closes that by asserting all four recomposed
    * equal what this network resolves at that height, so a differential here is
    * a statement about this upgrade rather than about a composition that
    * resembles it.
    */
  private def berlinWithout(dropped: Component): UpgradeRules => UpgradeRules =
    _ => ethereum.Upgrades.muirGlacier.adopting(theBerlinFour.filterNot(_.id == dropped.id)*)

  /** What declaring an account and a slot costs, back at nothing.
    *
    * ==The one row here that separates admitting the format from charging the
    * declaration==
    *
    * Withdrawing EIP-2930 whole does both at once, so its row counts every
    * entry whose envelope the rules stop admitting -- which measures the
    * envelope and not the declaration. This drops the two prices alone, leaving
    * the format admitted, so what moves is the entries whose intrinsic charge
    * the declaration decides.
    *
    * Zero is what the fork below charges: the schedule carries the two members
    * at that value everywhere the format is not admitted, so this is a revert
    * rather than a figure invented for the differential.
    */
  private val withoutTheDeclarationCharge: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(schedule =
          rules.evm.schedule.copy(
            transactionAccessListAddress = BigInt(0),
            transactionAccessListStorageKey = BigInt(0)
          )
        )
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
    *
    * ==At the highest fork here, no row is bounded by the directory named for
    * its proposal, and three rows have no such directory at all==
    *
    * The rows for that fork were each PREDICTED from the corpus's own directory
    * listing before being measured, and all six predictions were wrong. Grouping
    * the cases each row moves by the directory they come from says why, and the
    * answer is not a refinement of the file-name reading but its refutation:
    *
    *   - Three of the six proposals -- the curve repricing, the trie-size
    *     repricing and the calldata repricing -- are named by NO directory in
    *     the tier, and their rows are 191, 83 and 1382. For the first two every
    *     deciding case sits under a family named for an earlier fork. For the
    *     third the bulk sits under families named for OTHER proposals of this
    *     same fork, because what it charges is spent by any case carrying
    *     calldata whatever that case was written to test.
    *   - The trie-size row is the sharpest of those: 76 of its 83 cases come
    *     from the directory named for the code-hash proposal two forks below,
    *     because repricing that operation from 400 to 700 moves every case that
    *     spends it.
    *   - Where a directory IS named for the proposal it does not bound the row
    *     either. The compression native's 73 cases are joined by 5 from the
    *     static-call family, which parameterizes over precompile addresses and
    *     reaches this one among them; and the chain-identifier row takes 2 of
    *     its 3 cases from the opcode family named for the FIRST fork, leaving
    *     the directory carrying that proposal's own name contributing one.
    *
    * **So a per-proposal row is a claim about what the corpus SPENDS, not about
    * what it is filed under.** A repricing is spent by every case that executes
    * the operation at all, which is why the four repricings here draw on
    * between three and twelve directories each while the two additions draw on
    * two apiece.
    *
    * ==At the highest fork here, one row is legitimately ZERO and the widest
    * row is the least specific==
    *
    * The four rows of that fork are the only group in this matrix where a
    * reader ranking the proposals by their figures would be misled about every
    * one of them, so each carries its own reading:
    *
    *   - **EIP-2718 is 0, and it is neither uncertified nor an artifact.** That
    *     document introduces no transaction type -- its payload is *"defined in
    *     future EIPs"* -- so at this fork the only tagged format that exists is
    *     the one the document beside it defines. Withdrawing either leaves the
    *     same rules admitting the same one format, which forecloses a partition
    *     of this fork's four before any differential is run. What certifies the
    *     envelope is not a differential at all: 154 entries here publish a
    *     receipt whose octets this build reproduces byte for byte, and every one
    *     of those receipts begins with the type tag.
    *   - **EIP-2929 at 2109 is the widest row over this tier and the least
    *     specific.** It reprices eleven operations most executing cases touch,
    *     so a case that reads a balance, a code size, a code hash, a storage
    *     slot, makes a call or self-destructs changes its gas used, its sender's
    *     balance, its beneficiary's balance and therefore its root -- whatever
    *     it was written to test. The row measures how much of the tier reaches
    *     state at all.
    *   - **EIP-2930 at 297 measures the ENVELOPE, not the declaration.**
    *     Withdrawing it stops the fork admitting the tagged format, so what
    *     moves is every entry carrying one that the fork otherwise settles: 154
    *     admitted and 143 refused for something other than their format. The
    *     one typed entry that does not move carries the tag of a format this
    *     fork does not admit either way.
    *   - **The declaration charge is the row that separates the two questions**,
    *     and it is the only place in this fork where the corpus supplies both
    *     halves. Dropping the two prices alone leaves the format admitted, so
    *     what moves is decided by the declaration and by nothing else.
    *
    * ==What the declaration row does NOT count, measured rather than inferred==
    *
    * 252 entries of this tier declare something the fork prices, and 154 of them
    * answer differently when the price is removed: 123 that the tier expects to
    * be refused, and 31 that it expects to settle. The remaining 98 are settled
    * entries whose verdict the price cannot move, and the reason is a property
    * of gas rather than of the corpus -- **a transaction that runs out of gas is
    * charged its whole limit whatever its intrinsic charge was.** All 98 come
    * from one family, `eip2930_access_list/tx_intrinsic_gas`, which sets the
    * limit at exactly the intrinsic charge and calls code that cannot run in
    * what is left; removing the declaration's price leaves more gas for an
    * invocation that still exhausts it, and the beneficiary is credited the same
    * limit either way.
    *
    * So the row is a floor on what the corpus decides about the declaration and
    * not a census of what declares: a differential over a repricing can only see
    * a case whose OUTCOME the figure changes.
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
      ("EIP-145", CertificationCorpora.GeneratedConstantinopleFixCorpus, withoutTheShifts, 7),
      ("EIP-1052", CertificationCorpora.GeneratedConstantinopleFixCorpus, withoutExtCodeHash, 77),
      ("EIP-1014", CertificationCorpora.GeneratedConstantinopleFixCorpus, withoutSaltedCreation, 42),
      ("EIP-1283", CertificationCorpora.LegacyConstantinopleStateCorpus, withoutNetMetering, 6543),
      ("EIP-198", CertificationCorpora.LegacyByzantiumStateCorpus, withoutModularExponentiation, 340),
      ("EIP-211", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheReturnDataBuffer, 410),
      ("EIP-211", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheReturnDataBuffer, 47),
      ("EIP-214", CertificationCorpora.GeneratedByzantiumCorpus, withoutStaticCall, 267),
      ("EIP-214", CertificationCorpora.LegacyByzantiumStateCorpus, withoutStaticCall, 433),
      ("EIP-649", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheRewardReduction, 0),
      ("EIP-649", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheRewardReduction, 0),
      ("EIP-658", CertificationCorpora.GeneratedByzantiumCorpus, withoutTheStatusByte, 1834),
      ("EIP-658", CertificationCorpora.LegacyByzantiumStateCorpus, withoutTheStatusByte, 0),
      ("EIP-152", CertificationCorpora.GeneratedIstanbulCorpus, withoutBlake2fCompression, 78),
      ("EIP-1108", CertificationCorpora.GeneratedIstanbulCorpus, withoutTheCurveReprice, 191),
      ("EIP-1344", CertificationCorpora.GeneratedIstanbulCorpus, withoutTheChainIdOpcode, 3),
      ("EIP-1884", CertificationCorpora.GeneratedIstanbulCorpus, withoutTheTrieSizeReprice, 83),
      ("EIP-2028", CertificationCorpora.GeneratedIstanbulCorpus, withoutTheCallDataReprice, 1382),
      ("EIP-2200", CertificationCorpora.GeneratedIstanbulCorpus, withoutTheStorageSentry, 1070),
      ("EIP-2565", CertificationCorpora.GeneratedBerlinCorpus, berlinWithout(Eip2565.component), 186),
      ("EIP-2718", CertificationCorpora.GeneratedBerlinCorpus, berlinWithout(Eip2718.component), 0),
      ("EIP-2929", CertificationCorpora.GeneratedBerlinCorpus, berlinWithout(Eip2929.component), 2109),
      ("EIP-2930", CertificationCorpora.GeneratedBerlinCorpus, berlinWithout(Eip2930.component), 297),
      ("EIP-2930 declaration charge", CertificationCorpora.GeneratedBerlinCorpus, withoutTheDeclarationCharge, 154)
    )

  /** One group of the rows above rerun, once each, keyed by the proposal and
    * the corpus.
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
    * ==This is where the certification run spends its time, and it is one
    * corpus rather than a tier==
    *
    * One row is one pass over one corpus, and the matrix is the dominant cost
    * of the whole suite. It grows with the product of the proposals asserted
    * and the tiers they are asserted against rather than with the number of
    * tests. **A row removed to make the suite quicker is a proposal nothing
    * measures the corpora against**, which is the trade to refuse rather than
    * the saving to take. What may be taken instead is running the expensive
    * rows less often, which is what [[heavyRows]] below does.
    *
    * **A pass does NOT cost the same per case whichever tier it is over.**
    * Measured over all twenty-six rows, an unmodified pass costs 1.80 ms a case
    * over the generated tier at the earlier fork, 2.20 over the legacy tier
    * there, 5.47 over the generated tier at the later fork and 12.21 over the
    * legacy tier there -- a spread of 6.8 times, not the uniform figure a
    * smaller sample suggested.
    *
    * **So "the legacy tier is expensive" is false**, and the same 2394 files
    * demonstrate it: read at the earlier fork they are the second CHEAPEST
    * corpus per case, and read at the later one the dearest. Two things
    * compound there -- 4899 cases against 3036, and each case 5.5 times dearer,
    * because that fork is where the curve and modular-exponentiation natives
    * arrive and where the cases exercising them begin to answer.
    *
    * ==Forced outside `forAll`, like [[assembled]] and for its reason==
    *
    * The initializer cancels where there is no corpus, and a cancellation
    * raised inside a table's handler is reported as a failure. So each property
    * below reads this before entering `forAll`, never inside one.
    */
  private def movedFor(
      rows: Vector[(String, String, UpgradeRules => UpgradeRules, Int)]
  ): Map[(String, String), Vector[String]] =
    val reports = assembled
    rows.map { case (proposal, corpus, without, _) =>
      (proposal, corpus) -> movedBy(reports, corpus, without)
    }.toMap

  /** The corpora one rerun of which costs the better part of a minute.
    *
    * ==Keyed on the corpus because that is what the measurement found==
    *
    * Timed per row over all twenty-six, the legacy tier at the later fork is
    * 81% of the matrix: nine rows at 55.8 seconds each against 114.3 seconds
    * for the other seventeen together. Neither of the two shapes a reader
    * expects is what the numbers show -- it is not a tier, because the same
    * files at the earlier fork are among the cheapest rows here, and it is not
    * one or two outlying proposals, because eight of that corpus's nine rows
    * sit within 3% of their own mean.
    *
    * **The split is clean, which is what makes a corpus the right key.** The
    * cheapest row of this set costs 19.7 seconds and the dearest row outside it
    * 10.7, so no row named here is cheaper than any row not named -- a rule a
    * later reader can apply to a new row without re-timing anything.
    *
    * **A corpus added later is ordinary until measured, deliberately.** The
    * failure that direction produces is a slower run, which is loud; naming a
    * corpus here is what stops rows being asserted on every run, and that is
    * the quiet direction.
    *
    * ==The generated tier at the highest fork was measured against that rule
    * and stays ordinary==
    *
    * One rerun of it costs **8 seconds** over 2742 entries, taken as the
    * difference between two runs of this suite differing in exactly one rerun:
    * 373 seconds for the census properties alone against 381 for the census
    * plus one pass over that tier. That is below the 10.7 seconds this set's
    * own rule gives as the dearest row outside it, so naming it here would
    * break the clean split rather than preserve it -- and its five rows cost
    * about 41 seconds on every ordinary run, which is the price of asserting
    * that fork's coverage at all.
    */
  private val corporaRerunInMinutes: Set[String] =
    Set(
      CertificationCorpora.LegacyByzantiumStateCorpus,
      CertificationCorpora.LegacyConstantinopleStateCorpus,
      CertificationCorpora.LegacyConstantinopleFixStateCorpus
    )

  /** The rows every run asserts, and the rows only a heavy run does.
    *
    * `filter` and `filterNot` over one predicate, so the two partition
    * [[coverageRows]] exactly: a row cannot reach neither set, which written as
    * two hand-maintained vectors it could.
    */
  private val ordinaryRows = coverageRows.filterNot { case (_, corpus, _, _) =>
    corporaRerunInMinutes.contains(corpus)
  }
  private val heavyRows = coverageRows.filter { case (_, corpus, _, _) =>
    corporaRerunInMinutes.contains(corpus)
  }

  /** Each group's reruns, held separately so that excluding the tagged property
    * actually saves its time.
    *
    * **One memo over every row would defeat the tag entirely.** Whichever
    * property ran first would force all twenty-six reruns, so an excluded
    * property would still have been paid for and the ordinary run would be no
    * quicker -- a tag that reports a saving it did not make.
    */
  private lazy val movedPerOrdinaryRow: Map[(String, String), Vector[String]] = movedFor(ordinaryRows)
  private lazy val movedPerHeavyRow: Map[(String, String), Vector[String]] = movedFor(heavyRows)

  /** The rows as the matrix asserts them. The rule removed is absent because
    * the reruns above have already applied it, and a function renders as
    * nothing a reader can use in a failing row anyway.
    */
  private def coverageOf(rows: Vector[(String, String, UpgradeRules => UpgradeRules, Int)]) =
    Table(
      ("proposal", "corpus", "cases decided"),
      rows.map { case (proposal, corpus, _, decided) => (proposal, corpus, decided) }*
    )

  private val ordinaryCoverage = coverageOf(ordinaryRows)
  private val heavyCoverage = coverageOf(heavyRows)

  // `forAll` over an empty table PASSES, so either half emptied would certify
  // nothing while reporting green. The two properties below are what make the
  // set above a choice rather than a switch that can be turned all the way off,
  // and they are two because one body holding both assertions does not compile:
  // a discarded `Assertion` is a hard error here.
  property("the coverage matrix leaves rows an ordinary run asserts") {
    assert(ordinaryRows.nonEmpty, "every row is behind the tag, so an ordinary run asserts no coverage at all")
  }

  property("the coverage matrix leaves rows only a heavy run asserts") {
    assert(heavyRows.nonEmpty, "no row is behind the tag, so the tagged property asserts nothing")
  }

  /** One group's rows checked against what the matrix records for them.
    *
    * Shared by the two properties below so the assertion cannot drift between
    * them: they differ in which rows they cover and in nothing else, and two
    * copies of a message is how the halves stop reporting alike.
    *
    * **The reruns are forced by the caller, never here.** Each property below
    * binds its own map first, for the reason [[assembled]] gives -- a
    * cancellation raised inside a table's handler is reported as a failure.
    */
  private def decidesAsRecorded(
      moved: Map[(String, String), Vector[String]],
      rows: TableFor3[String, String, Int]
  ) =
    forAll(rows) { (proposal: String, corpus: String, decided: Int) =>
      val names = moved((proposal, corpus))
      assert(
        names.length == decided,
        s"$corpus decides ${names.length.toString} cases on $proposal rather than ${decided.toString}"
      )
    }

  property("each tier decides the cases the coverage matrix records") {
    val moved = movedPerOrdinaryRow
    decidesAsRecorded(moved, ordinaryCoverage)
  }

  property("each tier decides the cases the coverage matrix records, over the corpora that cost minutes", Heavy) {
    val moved = movedPerHeavyRow
    decidesAsRecorded(moved, heavyCoverage)
  }

  property("the one case the coverage matrix records for the bound on deployed code is the one named") {
    // The count above would still read as coverage if this case were dropped and
    // an unrelated one began to move, which for a rule reached by exactly one
    // case is the whole of the risk. Naming it is what closes that.
    //
    // Recorded as a coverage fact and not as a complaint: what pins EIP-170 is
    // its own unit coverage, and a reader who takes a certified fork to be one
    // whose every proposal the published corpora exercise would be wrong here.
    val moved = movedPerOrdinaryRow(("EIP-170", CertificationCorpora.LegacyEip158StateCorpus))
    assert(
      moved == Vector("codesizeOOGInvalidSize[d0g0v0]"),
      s"the bound decides these cases: ${moved.mkString(", ")}"
    )
  }

  property("the four recomposed from the fork below are the rules the Berlin tier is resolved to") {
    // Four of that tier's five rows withdraw a proposal by rebuilding the
    // upgrade without it rather than by reverting the figures it moved, which
    // is the only shape two of them admit at all. What that buys has to be paid
    // for here: a recomposition is right by construction and says nothing about
    // the schedule, so without this the four differentials would be statements
    // about a composition that merely resembles what this network runs.
    val schedule = registry
      .at(ethereum.Mainnet.network.chainId)
      .getOrElse(fail("no schedule for " + ethereum.Mainnet.network.name))
    assert(
      ethereum.Upgrades.muirGlacier.adopting(theBerlinFour*) ==
        schedule.at(UInt64.fromBits(CertificationCorpora.EthereumBerlinStarts), UInt64.Zero),
      "the four withdrawn from below do not recompose to what this network resolves at block " +
        CertificationCorpora.EthereumBerlinStarts.toString
    )
  }

  property("the height the Berlin tier is resolved at holds that fork's rules and not its neighbours'") {
    // The upgrade below this one changes nothing a state fixture can see -- it
    // moves a difficulty delay and no state test settles a header -- so this
    // tier resolved one activation too low would run under rules that differ
    // from these in nothing the corpus reads, and every case would still pass.
    // That makes it the one height in this harness whose neighbour a divergence
    // could not catch.
    val schedule = registry
      .at(ethereum.Mainnet.network.chainId)
      .getOrElse(fail("no schedule for " + ethereum.Mainnet.network.name))
    assert(
      schedule.at(UInt64.fromBits(CertificationCorpora.EthereumBerlinStarts), UInt64.Zero) ==
        ethereum.Upgrades.berlin,
      "the Berlin tier is resolved at block " + CertificationCorpora.EthereumBerlinStarts.toString +
        ", which does not hold this network's Berlin rules"
    )
  }

  property("the height the Istanbul tier is resolved at holds that fork's rules and not its neighbours'") {
    // The property below establishes that this height is SOME activation on
    // this network. That is not the same claim as its being the right one, and
    // a corpus filled for one fork and resolved under the neighbouring fork's
    // rules is precisely the failure the heights in `CertificationCorpora` are
    // duplicated to catch. Two entries already share a height on this schedule,
    // so "an activation is here" is demonstrably weaker than "this activation
    // is here".
    val schedule = registry
      .at(ethereum.Mainnet.network.chainId)
      .getOrElse(fail("no schedule for " + ethereum.Mainnet.network.name))
    assert(
      schedule.at(UInt64.fromBits(CertificationCorpora.EthereumIstanbulStarts), UInt64.Zero) ==
        ethereum.Upgrades.istanbul,
      "the Istanbul tier is resolved at block " + CertificationCorpora.EthereumIstanbulStarts.toString +
        ", which does not hold this network's Istanbul rules"
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
