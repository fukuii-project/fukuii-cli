package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}
import org.fukuii.chainspec.{Activation, UpgradeRules}
import org.fukuii.evm.{ContractAddress, Opcode, Word}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** Agharta's rule set against Ethereum Classic's own state fixtures.
  *
  * ==This tier is THIN, and the figure is stated before anything else==
  *
  * **Six of fifty-four cases, in five of the forty-five files.** A build that
  * adopted none of EIP-145, EIP-1014 or EIP-1052 at this height fails exactly
  * six registrations and passes the other forty-eight, so the fork-below
  * control here reads **6** where the same control at Atlantis reads **22**.
  *
  * **That is a property of the upgrade and not a defect in the corpus.**
  * Atlantis carried ten proposals and this one carries three, two of which add
  * a single operation apiece. But the consequence is real and this tier must
  * not be read through [[ClassicAtlantisStateCertificationSpec]]'s framing:
  * **this control is roughly a quarter as informative as the one it is modelled
  * on**, and a reader comparing the two files should compare the numbers rather
  * than the confidence of the prose.
  *
  * What keeps it from being vacuous is that the six are a clean partition over
  * the three proposals -- two cases each, no case decided by two of them -- so
  * the tier says something about every proposal this upgrade adopts rather than
  * a great deal about one. The per-proposal differentials below are the
  * deliverable; the union is not.
  *
  * ==Why this tier rather than a published one==
  *
  * [[ClassicAtlantisStateCertificationSpec]] states the reasoning and it holds
  * here, re-measured at this fork's own corpus rather than inherited. The
  * generated tier's `for_constantinoplefix` directory signs 1,916 of its 1,963
  * cases for chain 1, so resolving it through this chain refuses nearly all of
  * them as signed for another chain; of the 43 that are unprotected, 33 execute
  * none of this upgrade's five operations. The legacy tier's `ConstantinopleFix`
  * label publishes 10,569 post entries and **not one of them carries
  * `txbytes`**, so a stated sender stands, the chain is never consulted, and its
  * expectations are satisfied by either network's rules.
  *
  * **That second objection composes to something stronger here than at
  * Atlantis, and `org.fukuii.chainspec.networks.SharedHistorySpec` asserts
  * it**: this rule set's machine, settlement and admission facets equal the
  * other network's at its own counterpart height, so a legacy tier run through
  * this network would pass and would have passed identically through the other.
  * A tier that agrees whichever network it is resolved through is not evidence
  * about either.
  *
  * ==What these expectations rest on, and where the chain stops==
  *
  * `fukuii-project/fukuii-tests` @
  * `a6b97cd302ff7cd6b2165b64c0bac65d8ee4d172`, branch `main`,
  * `networks/ethereumclassic/mainnet/state`. **The path's own tree object is
  * `fe6fec1d956c8bc870b0e4c8705855c0a3e79c62`**, cited beside the commit
  * because it is the half that survives a rebase --
  * [[ClassicAtlantisStateCertificationSpec]] gives that reasoning and the
  * incident behind it. That tree is byte-identical to the one the Atlantis and
  * Die Hard tiers read, so all three tiers read the same forty-five files.
  *
  * **Every one of the forty-five names one oracle, and it is a client this
  * project has worked on.** The `oracle` key reads *"core-geth production"* and
  * the `oracle-version` key beside it corrects that in the same block, naming
  * `core-geth modernized 1.13.0-26975d6a` and stating why production `v1.12.21`
  * cannot have been the generator: its `t8n` fork table has no name for three
  * of the twelve labels these files are filled at. **So `oracle` alone
  * under-reads the provenance here**, which is the hazard the Atlantis tier
  * records in general terms and this is a live instance of.
  *
  * All five of the discriminating files carry a `verification` key recording a
  * second pass through that same build's own `statetest` runner. **A second
  * runner is not a second implementation**, so what the pair rules out is a
  * fault in either runner's accounting, never a rule that build reads wrongly.
  *
  * **So a pass here establishes that this build and that one agree at
  * 9,573,000. It does not establish that either is right.**
  *
  * ==Two things ARE stronger than that, and both are checkable==
  *
  * **One case carries a recorded mutation score.**
  * `accounts/extcodehash_semantics.json`'s `_info.wrongBuildScores` states that
  * a client with the `Empty()` branch removed from `opExtCodeHash` fails at the
  * five upgrades where EXTCODEHASH exists and passes at the seven where it does
  * not -- correct in both directions rather than a bare failure count. Seven of
  * the forty-five files carry such a score and this is the only one among the
  * five that decide this fork.
  *
  * **And the salted-creation pair has an oracle no client supplied.** The
  * fixture publishes a state root, which names nothing a reader can check; the
  * two addresses its factory must produce are derivable from EIP-1014's own
  * formula, and [[org.fukuii.evm.Create2AddressPropSpec]] already holds this
  * build's derivation against all seven examples that document publishes. The
  * registration below ties the fixture's own creator, initialization code and
  * salts to that same derivation, so the case stops resting only on a root.
  *
  * ==The six cases assert a PRICE as well as a presence==
  *
  * Invisible from the file names and worth stating so the assertion is not
  * later weakened by a reader who takes them for availability probes. Every one
  * of the five files sets `gasPrice` to `0x3b9aca00` and **omits the coinbase
  * from `pre`**, so the fee payment creates that account and its balance --
  * `gasUsed` times `gasPrice` -- is inside the published root. A build pricing
  * EXTCODEHASH at 700 rather than 400, or omitting the hashing term CREATE2
  * charges, moves the root and fails the case.
  *
  * ==One proposal is two-thirds covered, and the uncovered third is its
  * namesake==
  *
  * **SHL's SEMANTICS are asserted by no tier available to this file.**
  * `shl_availability` is a marker probe -- it runs the operation, discards the
  * result and stores a non-zero marker -- so a build whose SHL shifts the wrong
  * way, or returns nothing useful, writes the same marker and passes. SHR and
  * SAR are asserted semantically and paired against each other, so a build
  * wiring one to the other fails.
  *
  * **This is a limit on the TIER and not a gap in the build**, and the two are
  * easy to confuse. `org.fukuii.evm.WordSpec` carries seventeen shift
  * assertions including the low-bits boundary, so the machine's own layer
  * certifies the semantics while this layer certifies presence and price at the
  * fork. Both are needed and neither substitutes for the other.
  *
  * **The price of SHL is a third thing again and IS certified**, so the three
  * claims must not be collapsed into one: presence here, semantics in the
  * machine's own layer, and price in the published generated tier on the other
  * network's side of the schedule. Repricing this operation moves
  * `state_tests/for_constantinoplefix` cases that name it directly --
  * `test_max_stack[fork_ConstantinopleFix-opcode_SHL-state_test]` among them --
  * each reporting a cumulative-gas difference of exactly the repricing beside
  * the state root. **Price and semantics are one word apart**, and a reader
  * relaying this file's coverage gap should relay the middle claim only.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason the Atlantis tier gives: a
  * cancelled test is counted by nothing, so a build whose corpus vanished
  * reports the same executed total as one that ran it.
  *
  * ==The figures are literals, so a corpus that shrank is a failure==
  *
  * Every count below is stated rather than derived from the run.
  */
class ClassicAghartaStateCertificationSpec extends AnyFlatSpec:

  /** Files the tier states its cases in. */
  private val Files: Int = 45

  /** Runnable combinations across those files, at this label. */
  private val Cases: Int = 54

  /** Cases this build answers, which is every one the tier states here. */
  private val Certified: Int = 54

  /** Cases that answer differently once the three bitwise shifts leave the
    * operation table.
    *
    * Named rather than counted, for the reason the Atlantis tier gives about
    * its own pair: a corpus that lost one of these and gained an unrelated case
    * would report the same number. The two are not interchangeable -- the first
    * is a marker probe and the second is the only semantic assertion EIP-145
    * gets here, so losing the second silently would cost the proposal its whole
    * semantic coverage while the count stayed at two.
    */
  private val WithoutTheShiftsTheseMove: Vector[String] =
    Vector("shiftRightAcrossUpgrades[d0g0v0]", "shl_availability[d0g0v0]")

  /** Cases that answer differently once EXTCODEHASH leaves the table.
    *
    * The availability probe and the semantic case, and the second of the two is
    * the one carrying this corpus's only recorded mutation score among the five
    * files that decide this fork.
    */
  private val WithoutExtCodeHashTheseMove: Vector[String] =
    Vector("extcodehashSemanticsAcrossUpgrades[d0g0v0]", "extcodehash_availability[d0g0v0]")

  /** Cases that answer differently once salted creation leaves the table.
    *
    * Both are the same fixture at two data indexes, differing only in the salt
    * the factory reads from calldata. **That is what makes the pair an
    * assertion about the derivation rather than about the operation's
    * presence**: a build that had the instruction and ignored the salt would
    * produce one address twice and fail the second alone.
    */
  private val WithoutSaltedCreationTheseMove: Vector[String] =
    Vector("create2AddressDerivationAcrossUpgrades[d0g0v0]", "create2AddressDerivationAcrossUpgrades[d1g0v0]")

  /** Cases that answer differently once all three proposals leave at once.
    *
    * Six, and it must equal the three pairs' union rather than merely their
    * total -- which the registration below asserts by set rather than by count,
    * because three disjoint pairs and one proposal deciding all six sum to the
    * same six.
    */
  private val MovedWithoutAllThree: Int = 6

  /** Cases that answer differently when this tier is resolved one fork lower.
    *
    * **Six, where the same measurement at Atlantis reaches twenty-two.** The
    * control the whole registration depends on: a tier whose expectations are
    * satisfied by the rules of the fork below it is not evidence about the fork
    * it is named for. Six is enough to make it evidence and is thin enough that
    * the header states the comparison rather than leaving a reader to assume
    * the precedent's strength carried over.
    *
    * Both labels sit above Die Hard's boundary, so every case carries
    * byte-identical transaction bytes at the two and what differs is what the
    * rules do with them.
    */
  private val MovedAtTheForkBelow: Int = 6

  /** Cases that disagree when Agharta's rules are asked the expectations the
    * fork below files.
    */
  private val DivergingAtTheLabelBelow: Int = 6

  /** Cases that disagree when Agharta's rules are asked the expectations the
    * fork above files.
    *
    * Twenty-seven, against six at the fork below. **The asymmetry is the
    * upgrade above being the larger one**, and it is asserted because it is the
    * half of this tier's discrimination that is not thin: whatever this file
    * cannot say about the boundary below it, it separates this fork from the
    * next cleanly.
    */
  private val DivergingAtTheLabelAbove: Int = 27

  /** The label of the fork below this one. */
  private val LabelBelow: String = "ETC_Atlantis"

  /** The label of the fork above this one. */
  private val LabelAbove: String = "ETC_Phoenix"

  /** Files read when the label names an upgrade this corpus files nothing
    * under, and the cases each of them then declines to answer.
    *
    * The reader's own control: it dispatches on the post key, so a label the
    * corpus does not carry must report every file as stating no expectation
    * rather than silently matching something.
    */
  private val UnfilledLabel: String = "ETC_Thanos"

  /** The account the salted-creation fixture creates from, and the body it
    * deploys.
    *
    * Both are read out of `accounts/create2_address_derivation.json`'s own
    * `pre`: the factory at that address holds the initialization code as a
    * PUSH32 immediate, stores it at memory zero, reads a salt from calldata and
    * creates ten bytes from offset zero. The body is those ten bytes.
    */
  private val SaltedCreator: String = "0x0000000000000000000000000000000000001000"
  private val SaltedInitCode: String = "0x600a80600080396000f3"

  /** The two addresses that fixture's two data indexes must produce.
    *
    * Derived from EIP-1014's formula --
    * `keccak256(0xff ++ creator ++ salt ++ keccak256(initCode))` truncated to
    * twenty bytes -- with the creator and body above and the salts the two
    * calldata payloads carry, which are the words zero and one.
    *
    * **This is the only oracle in this file that no client supplied**, which is
    * why it is asserted by name rather than left inside a state root. The
    * document is `ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1014.md`, Final, and
    * [[org.fukuii.evm.Create2AddressPropSpec]] holds this build's derivation
    * against all seven worked examples it publishes -- so the registration
    * below adds the fixture's own inputs to a derivation already pinned from
    * outside this project.
    */
  private val SaltZeroProduces: String = "0x90751e96a3100a0ae08dc2d21dc0d5a21cd8ea8b"
  private val SaltOneProduces: String = "0x9f7419e4d22cbc1cfb32bcf479a7955fc0da9ec3"

  /** The two salts, written out rather than constructed.
    *
    * A salt is a full word and is encoded as its thirty-two bytes, so these are
    * the two calldata payloads the fixture carries verbatim. Written as
    * literals because a consensus input assembled by an expression is one an
    * auditor has to evaluate before they can check it.
    */
  private val SaltZero: String = "0x0000000000000000000000000000000000000000000000000000000000000000"
  private val SaltOne: String = "0x0000000000000000000000000000000000000000000000000000000000000001"

  private val report: CorpusReport =
    ClassicStateCorpus.agharta.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The same tree and label, resolved at `height` with `change` applied to
    * whatever the schedule answered there.
    */
  private def under(height: Long, change: UpgradeRules => UpgradeRules): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", ClassicStateCorpus.AghartaFork, height, change)
      .getOrElse(fail("assembled once and not the second time"))

  /** The same tree at another label, resolved at this fork's own height. */
  private def readAs(label: String): CorpusReport =
    ClassicStateCorpus
      .reportAt("control", label, ClassicStateCorpus.AghartaStarts, identity)
      .getOrElse(fail("assembled once and not the second time"))

  /** Which cases answer differently under an altered run, by name.
    *
    * The pairing is checked before it is relied on, for the reason the Die Hard
    * tier states: `zip` truncates to the shorter side rather than complaining,
    * so a control yielding fewer outcomes would report a LOW count -- which
    * reads as a corpus that decides less rather than as a control that went
    * wrong.
    */
  private def moved(altered: CorpusReport): Vector[String] =
    if report.outcomes.map(_.name) != altered.outcomes.map(_.name) then
      fail(
        "the control did not answer for the same cases in the same order: " +
          report.casesFound.toString + " outcomes first and " + altered.casesFound.toString + " on the control"
      )
    else report.outcomes.zip(altered.outcomes).collect { case (before, after) if before != after => before.name }

  /** The one case in this build where a mutation is applied at this fork's own
    * height, which is where every differential below is read.
    */
  private def movedAtThisFork(change: UpgradeRules => UpgradeRules): Vector[String] =
    moved(under(ClassicStateCorpus.AghartaStarts, change))

  /** The three bitwise shifts. */
  private val withoutTheShifts: UpgradeRules => UpgradeRules =
    rules =>
      rules.copy(evm =
        rules.evm.copy(table = rules.evm.table.removing(Opcode.Shl).removing(Opcode.Shr).removing(Opcode.Sar))
      )

  /** The operation that reads an account's code hash. */
  private val withoutExtCodeHash: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.ExtCodeHash)))

  /** The creation whose address is settled by a salt rather than by a count. */
  private val withoutSaltedCreation: UpgradeRules => UpgradeRules =
    rules => rules.copy(evm = rules.evm.copy(table = rules.evm.table.removing(Opcode.Create2)))

  /** All three of this upgrade's proposals withdrawn at once, which is the
    * build that adopted nothing here.
    */
  private val withoutAllThree: UpgradeRules => UpgradeRules =
    withoutTheShifts.andThen(withoutExtCodeHash).andThen(withoutSaltedCreation)

  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).getOrElse(fail("not hex: " + hex))

  private def address(hex: String): Address =
    Address.fromHex(hex).getOrElse(fail("not an address: " + hex))

  private def saltedAddress(salt: String): Address =
    ContractAddress.create2(address(SaltedCreator), Word.fromBytes(bytes(salt)), bytes(SaltedInitCode))

  "this chain's state tier at Agharta" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every case the tier states at this label" in
    assert(
      report.casesFound == Cases,
      "found " + report.casesFound.toString + " cases rather than " + Cases.toString + ": " + report.describe
    )

  it should "agree with every case it answers" in
    assert(report.diverged.isEmpty, report.describe)

  it should "answer the stated number of them" in
    assert(
      report.agreed.length == Certified,
      "certified " + report.agreed.length.toString + " rather than " + Certified.toString + ": " + report.describe
    )

  it should "skip nothing at all" in
    assert(
      report.skipped.isEmpty,
      "every file carries an expectation at this label, so a skip is a reader fault rather than a gap in the " +
        "corpus: " + report.describe
    )

  "the height this tier is resolved at" should "be an activation on this network's schedule" in {
    val schedule = KnownNetworks.registry.toOption
      .flatMap(_.at(ethereumclassic.Mainnet.network.chainId))
      .getOrElse(fail("this network is not in the registry"))
    assert(
      schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(ClassicStateCorpus.AghartaStarts))),
      "resolving a corpus through a height no fork begins at certifies a neighboring fork's rules under this " +
        "one's name: " + schedule.forkPoints.toString
    )
  }

  "the harness" should "leave every case answered when nothing is altered" in
    assert(
      movedAtThisFork(identity).isEmpty,
      "the control path must not perturb the run, or every count below measures the control: " +
        under(ClassicStateCorpus.AghartaStarts, identity).describe
    )

  it should "answer nothing at a label this corpus files no expectation under" in {
    val unfilled = readAs(UnfilledLabel)
    assert(
      unfilled.filesRead == report.filesRead && unfilled.agreed.isEmpty &&
        unfilled.skipped.length == unfilled.casesFound,
      "the reader dispatches on the post key, so an unfilled label must report every file as stating no " +
        "expectation rather than matching something: " + unfilled.describe
    )
  }

  "a build without EIP-145" should "lose both cases that shift, named rather than counted" in
    assert(
      movedAtThisFork(withoutTheShifts).sorted == WithoutTheShiftsTheseMove,
      "one case is a marker probe and one is this proposal's only semantic assertion here, so a corpus that " +
        "lost the second and gained an unrelated case would report the same count: " +
        movedAtThisFork(withoutTheShifts).mkString(", ")
    )

  "a build without EIP-1052" should "lose both cases that read a code hash, named rather than counted" in
    assert(
      movedAtThisFork(withoutExtCodeHash).sorted == WithoutExtCodeHashTheseMove,
      "the availability probe and the semantic case, the second of which carries this corpus's own recorded " +
        "mutation score: " + movedAtThisFork(withoutExtCodeHash).mkString(", ")
    )

  "a build without EIP-1014" should "lose both salted creations, named rather than counted" in
    assert(
      movedAtThisFork(withoutSaltedCreation).sorted == WithoutSaltedCreationTheseMove,
      "the two differ only in the salt, so a build that had the instruction and ignored the salt would fail " +
        "the second alone: " + movedAtThisFork(withoutSaltedCreation).mkString(", ")
    )

  "this upgrade's three proposals" should "decide six cases between them, and no case twice" in {
    val shifts = movedAtThisFork(withoutTheShifts).toSet
    val codeHash = movedAtThisFork(withoutExtCodeHash).toSet
    val salted = movedAtThisFork(withoutSaltedCreation).toSet
    val union = movedAtThisFork(withoutAllThree).toSet
    assert(
      (shifts & codeHash).isEmpty && (shifts & salted).isEmpty && (codeHash & salted).isEmpty &&
        union == (shifts ++ codeHash ++ salted) && union.size == MovedWithoutAllThree,
      "three disjoint pairs and one proposal deciding all six sum to the same six, so the partition is " +
        "asserted as SETS rather than as a total: shifts " + shifts.toVector.sorted.mkString(", ") +
        "; code hash " + codeHash.toVector.sorted.mkString(", ") +
        "; salted " + salted.toVector.sorted.mkString(", ") +
        "; union " + union.toVector.sorted.mkString(", ")
    )
  }

  "this tier resolved at the fork below" should "lose the cases that make it evidence about Agharta" in
    assert(
      moved(under(ClassicStateCorpus.AtlantisStarts, identity)).length == MovedAtTheForkBelow,
      "a tier satisfied by the rules of the fork below it says nothing about the fork it is named for, and " +
        "six is a thinner control than the twenty-two the fork below earns: " +
        under(ClassicStateCorpus.AtlantisStarts, identity).describe
    )

  "this tier read under the label below" should "disagree with Agharta's rules" in
    assert(
      readAs(LabelBelow).diverged.length == DivergingAtTheLabelBelow,
      "the label is what the reader dispatches on, so a tier agreeing with the fork below's expectations " +
        "under this fork's rules would not be evidence about either: " + readAs(LabelBelow).describe
    )

  "this tier read under the label above" should "disagree with Agharta's rules" in
    assert(
      readAs(LabelAbove).diverged.length == DivergingAtTheLabelAbove,
      "the upgrade above is the larger one, so this boundary is where the tier's discrimination is not thin: " +
        readAs(LabelAbove).describe
    )

  "the salted creations this tier certifies" should "land where the proposal's own formula puts them" in
    // The fixture publishes a state root and no address, so the two cases above
    // rest entirely on an oracle this project is not independent of. This is
    // the same two cases held against EIP-1014's derivation instead, which is
    // the only oracle here that no client supplied.
    assert(
      saltedAddress(SaltZero) == address(SaltZeroProduces) &&
        saltedAddress(SaltOne) == address(SaltOneProduces) &&
        saltedAddress(SaltZero) != saltedAddress(SaltOne),
      "the fixture's factory holds one creator and one body and reads its salt from calldata, so the two data " +
        "indexes must produce these two addresses and must not produce one address twice: salt zero gave " +
        saltedAddress(SaltZero).toHex + " and salt one gave " + saltedAddress(SaltOne).toHex
    )
