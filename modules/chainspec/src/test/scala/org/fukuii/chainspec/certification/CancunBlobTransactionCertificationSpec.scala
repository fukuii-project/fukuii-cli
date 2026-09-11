package org.fukuii.chainspec.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.proposals.eip.{Eip1153, Eip4844, Eip5656, Eip6780, Eip7516}
import org.fukuii.chainspec.{Component, ProposalId, UpgradeRules}
import org.fukuii.evm.fixtures.{FixtureCorpus, StateFixture, StatedFee}
import org.fukuii.evm.{BlobGas, BlobGasPrice}
import org.fukuii.types.{Transaction, TransactionType}

import java.nio.file.{Files, Path}

/** The blob transaction itself, against the three published directories that
  * state one.
  *
  * ==What this spec is FOR, given that another one already runs these corpora==
  *
  * `CertificationCorporaSpec` censuses all three and asserts that this build
  * agrees with every case in them. That is the pass/fail signal and it is not
  * repeated here. **What it cannot say is what the agreement is worth**, and
  * every figure below is an answer to that: which rules the directories can
  * discriminate, which they cannot, and by how many cases.
  *
  * The distinction has been paid for at every earlier phase of this fork.
  * `CertificationCorporaSpec` finds a directory named for a proposal
  * deciding four cases of which two could not disagree;
  * `CancunBlobGasCertificationSpec` found a corpus whose only refusals fail two
  * rules at once, so that dropping either one left it in full agreement. The
  * directories below are read on the assumption that they are thinner than
  * their names suggest, and each figure is the measurement of how much thinner.
  *
  * ==The reading is the production reader's, which is what makes these figures
  * about the corpus rather than about this file==
  *
  * `org.fukuii.evm.fixtures.StateFixture` decodes every case, including the two
  * fields the blob format adds. So a figure below counts what the harness
  * itself would run, and a reader that silently dropped a field would move
  * these counts rather than leaving them describing a file nobody parsed.
  *
  * ==What the three CAN discriminate==
  *
  *   - **The format.** All 914 cases across the first three state a transaction of a
  *     format only this document admits, so a rule set without it refuses every
  *     one of them for its type. The differential below measures that directly.
  *   - **The blob fee inside the balance requirement.** 144 cases expect
  *     `INSUFFICIENT_ACCOUNT_FUNDS`, and **every one of them is covered by its
  *     sender's balance once what it offered for its blobs is left out**. So a
  *     build that omitted the blob half of the requirement admits all 144 and
  *     diverges on all 144 -- this is the thickest single rule in the corpus.
  *   - **The CAP against the CHARGE, in the fee actually taken.** 450 of the 722
  *     accepted cases state a ceiling per unit of blob gas that differs from
  *     what their block charges, so a build that debited the ceiling rather than
  *     the charge settles them to a root the corpus does not publish.
  *   - **Every commitment, rather than the first.** Two of the four
  *     versioned-hash refusals are well-formed in their first commitment and
  *     malformed in their second.
  *   - **The operation's answer past the end of what a transaction carries.**
  *     24 of the 39 cases across the two operation directories run it against a
  *     transaction with no such field at all.
  *
  * ==What they CANNOT, which is the half worth stating loudest==
  *
  *   - **The blob charge above its floor, in anything that settles.** 874 of the
  *     875 cases in the transaction directory run at a charge of one, and the
  *     one that does not is a case the corpus REFUSES -- so the fee this build
  *     actually debits is `blobGas * 1` in every case that reaches settlement,
  *     and a build that ignored the derivation and used the constant one would
  *     settle every one of them identically. That is the same blind spot the
  *     charge-reporting operation's own directory has, met a second time from a
  *     different direction, and `org.fukuii.evm.BlobGasPriceSpec` is what covers
  *     it against a published table instead.
  *   - **The derivation is reached by exactly ONE case**, and only as a
  *     comparison: a transaction offering a ceiling of one against a block
  *     charging two. A build stuck at the floor admits it and diverges. One case
  *     is the whole of this tier's evidence that the charge moves at all.
  *   - **The commitment's DIGEST.** Admission reads the version byte and nothing
  *     under it, because the blob a commitment is taken over travels beside the
  *     transaction and no layer here holds it. No case in any of the three can
  *     distinguish a build that verified the digest from one that could not.
  *   - **A second blob transaction in the same block.** A state fixture is one
  *     transaction against an otherwise empty block, so the allowance each is
  *     measured against is always the fork's whole maximum. The rule that makes
  *     it a REMAINDER -- the one the specification states and one production
  *     client does not -- is unreachable from this tier at all.
  *   - **A blob transaction that DEPLOYS.** The ported directory names one and
  *     it is the only such case in the state tier. Its published bytes decode
  *     as no blob transaction at all -- this build and the specification both
  *     type that recipient as an address -- so the case is skipped rather than
  *     decided, and the rule
  *     `org.fukuii.execution.Refusal.FormatMayNotDeploy` is reached by nothing
  *     here. The assertion below pins the skip so that a corpus which later
  *     publishes readable bytes for it stops being silently uncertified.
  *
  * ==A fourth directory, and it is the ported tier's rather than the generated
  * one's==
  *
  * `ported_static/stEIP4844_blobtransactions` holds five cases restating four
  * of the same rules from an older suite, plus the deploying case above. It is
  * read because a second corpus's independent statement of a rule is worth more
  * than a second copy of the first -- the near-complement relationship the
  * legacy and generated tiers already have, at a much smaller scale.
  */
class CancunBlobTransactionCertificationSpec extends AnyFlatSpec:

  private val Adopted: Vector[Component] =
    Vector(Eip1153.component, Eip5656.component, Eip6780.component, Eip4844.component, Eip7516.component)

  private val BlobFormat: ProposalId = ProposalId.Eip(4844)

  private val BlobCharge: ProposalId = ProposalId.Eip(7516)

  /** The rules these corpora are resolved at, rebuilt here.
    *
    * The first assertion below is what establishes that this rebuild is the one
    * the harness used; without it every figure would be about some third rule
    * set.
    */
  private val composed: UpgradeRules = ethereum.Upgrades.shanghai.adopting(Adopted*)

  /** The same composition with the blob format and the operation that prices it
    * both withdrawn.
    *
    * **The pair, never the format alone.** Withdrawing the accounting leaves the
    * charge-reporting operation in the table with nothing to derive a charge
    * from, and `org.fukuii.evm.Interpreter` refuses that configuration rather
    * than answering it -- so a rerun under it would raise out of this spec
    * instead of reporting a divergence. `CertificationCorporaSpec`
    * records the same constraint for the same pair, on the vector its Cancun
    * rows are built from.
    */
  private val withoutBlobs: UpgradeRules => UpgradeRules =
    _ => ethereum.Upgrades.shanghai.adopting(Adopted.filterNot(held => Set(BlobFormat, BlobCharge).contains(held.id))*)

  private val Transactions: String = CertificationCorpora.GeneratedCancunBlobTransactionCorpus

  private val HashCost: String = CertificationCorpora.GeneratedCancunBlobHashCostCorpus

  private val HashContexts: String = CertificationCorpora.GeneratedCancunBlobHashContextCorpus

  /** Every case of one directory, decoded through the production reader.
    *
    * A directory that yields nothing is not silently an empty analysis: the
    * first assertion below pins each census, so a corpus that failed to decode
    * moves a figure rather than leaving every later count trivially satisfied.
    */
  private def casesUnder(relative: String): Vector[StateFixture] =
    FixtureCorpus.root.toVector
      .map(root => FixtureCorpus.generated(root).resolve(relative))
      .filter(Files.isDirectory(_))
      .flatMap(FixtureCorpus.jsonFilesUnder)
      .flatMap { file =>
        FixtureCorpus
          .read(file)
          .flatMap(StateFixture.decodeFile(file.getFileName.toString, _, "Cancun"))
          .toOption
          .toVector
          .flatMap(_.fixtures)
      }

  private val transactions: Vector[StateFixture] =
    casesUnder("state_tests/for_cancun/cancun/eip4844_blobs/blob_txs")

  private val hashCost: Vector[StateFixture] =
    casesUnder("state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode")

  private val hashContexts: Vector[StateFixture] =
    casesUnder("state_tests/for_cancun/cancun/eip4844_blobs/blobhash_opcode_contexts")

  private val ported: Vector[StateFixture] =
    casesUnder("state_tests/for_cancun/ported_static/stEIP4844_blobtransactions")

  /** The update fraction these rules resolve, which the charge is derived
    * through.
    *
    * Read from the rules under test rather than restated, so a figure below
    * cannot agree with a fraction that was changed -- and raised rather than
    * defaulted, because a zero would divide by zero and a wrong non-zero would
    * quietly move every charge.
    */
  private val UpdateFraction: BigInt =
    composed.evm.blobBaseFeeUpdateFraction.getOrElse(
      throw new IllegalStateException("the rules under test derive no blob charge")
    )

  /** What the block one case states charges per unit of blob gas. */
  private def chargeAt(fixture: StateFixture): BigInt =
    BlobGasPrice.at(fixture.block.excessBlobGas.map(_.toBigInt).getOrElse(BigInt(0)), UpdateFraction)

  private def blobGasOf(fixture: StateFixture): BigInt =
    BlobGas.spentOn(fixture.transaction.blobVersionedHashes)

  /** What the sender must hold for everything but its blobs.
    *
    * The CAP for gas, as admission compares it, and the value sent. This is
    * deliberately the requirement MINUS the blob half, because what every
    * figure below asks is whether the blob half is what decided the case.
    */
  private def requirementWithoutBlobs(fixture: StateFixture): BigInt =
    val cap = fixture.transaction.fee match
      case StatedFee.Fixed(gasPrice)   => gasPrice
      case StatedFee.Capped(maxFee, _) => maxFee
    fixture.transaction.gasLimit * cap + fixture.transaction.value

  private def balanceOf(fixture: StateFixture): BigInt =
    fixture.pre.get(fixture.transaction.sender).map(_.balance).getOrElse(BigInt(0))

  private def refusedAs(fixture: StateFixture, name: String): Boolean =
    fixture.expectation.rejection.exists(_.stated.exists(_.contains(name)))

  private def accepted(fixture: StateFixture): Boolean = fixture.expectation.rejection.isEmpty

  // ── What the figures below are figures ABOUT ───────────────────────────────

  "the composition recomposed from the fork below" should "be the rules these corpora are resolved at" in {
    val _ = assume(transactions.nonEmpty)
    val report = CertificationCorpora
      .rerun(Transactions, _ => composed)
      .getOrElse(fail("the harness assembles no corpus called " + Transactions))
    assert(
      report.diverged.isEmpty,
      "a recomposition that did not reproduce the rules the harness resolved this corpus at would make every " +
        "figure below about some third rule set: " + report.diverged.length.toString + " diverged"
    )
  }

  it should "decide every case in all three directories once the format is withdrawn" in {
    val _ = assume(transactions.nonEmpty)
    // The coarse differential, and the only one a rerun can express here: with
    // the format gone every case is refused for its TYPE, which is neither what
    // the accepted cases expect nor what the refused ones name. A finer
    // withdrawal is not available, because this document's rules are not
    // separately adoptable -- they arrive as one component and the corpus is
    // read under it or not at all.
    val moved = Vector(Transactions, HashCost, HashContexts).map { corpus =>
      CertificationCorpora
        .rerun(corpus, withoutBlobs)
        .getOrElse(fail("assembled once and not the second time: " + corpus))
        .diverged
        .length
    }
    assert(
      moved == Vector(875, 28, 11),
      "measured at 875, 28 and 11 cases decided by the format: " + moved.toString
    )
  }

  "the three directories" should "hold the cases this spec measures over" in {
    val _ = assume(transactions.nonEmpty)
    // Pinned as literals so a corpus that shrank is a failure rather than a
    // smaller pass, exactly as the census does for the same three.
    assert(
      transactions.length == 875 && hashCost.length == 28 && hashContexts.length == 11,
      "measured at 875, 28 and 11 decoded cases: " + transactions.length.toString + ", " +
        hashCost.length.toString + " and " + hashContexts.length.toString
    )
  }

  it should "state a blob transaction in every case of the transaction directory" in {
    val _ = assume(transactions.nonEmpty)
    val typed = transactions.count(_.transaction.kind == TransactionType.Blob)
    assert(typed == 875, "cases of the blob format: " + typed.toString + " of " + transactions.length.toString)
  }

  // ── What the transaction directory can discriminate ────────────────────────

  "the refusals the transaction directory publishes" should "be the six rules it names, counted" in {
    val _ = assume(transactions.nonEmpty)
    // The census of expectations, which is what every later figure is a subset
    // of. Four of the six are rules this document introduces; the other two are
    // rules it inherits and the directory happens to exercise.
    val census = Vector(
      "INSUFFICIENT_ACCOUNT_FUNDS" -> transactions.count(refusedAs(_, "INSUFFICIENT_ACCOUNT_FUNDS")),
      "TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH" ->
        transactions.count(refusedAs(_, "TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH")),
      "INSUFFICIENT_MAX_FEE_PER_BLOB_GAS" -> transactions.count(refusedAs(_, "INSUFFICIENT_MAX_FEE_PER_BLOB_GAS")),
      "INSUFFICIENT_MAX_FEE_PER_GAS" -> transactions.count(refusedAs(_, "INSUFFICIENT_MAX_FEE_PER_GAS")),
      "TYPE_3_TX_ZERO_BLOBS" -> transactions.count(refusedAs(_, "TYPE_3_TX_ZERO_BLOBS")),
      "TYPE_3_TX_BLOB_COUNT_EXCEEDED" -> transactions.count(refusedAs(_, "TYPE_3_TX_BLOB_COUNT_EXCEEDED"))
    )
    assert(
      census == Vector(
        "INSUFFICIENT_ACCOUNT_FUNDS" -> 144,
        "TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH" -> 4,
        "INSUFFICIENT_MAX_FEE_PER_BLOB_GAS" -> 2,
        "INSUFFICIENT_MAX_FEE_PER_GAS" -> 1,
        "TYPE_3_TX_ZERO_BLOBS" -> 1,
        "TYPE_3_TX_BLOB_COUNT_EXCEEDED" -> 1
      ),
      "the refusals this directory publishes: " + census.toString
    )
  }

  it should "leave every other case expecting to settle" in {
    val _ = assume(transactions.nonEmpty)
    // The closure: 153 refusals and 722 acceptances account for all 875, so no
    // group went unmeasured above.
    val settling = transactions.count(accepted)
    assert(
      settling == 722 && settling + 153 == transactions.length,
      "cases expecting to settle: " + settling.toString + " of " + transactions.length.toString
    )
  }

  "every balance refusal" should "turn on what the transaction offered for its blobs" in {
    val _ = assume(transactions.nonEmpty)
    // THE THICKEST RULE IN THE CORPUS, measured rather than assumed. Each of
    // these senders can cover its gas and its value; what it cannot cover is the
    // ceiling it offered per unit of blob gas times the blob gas it wanted. A
    // build computing the requirement without that term admits all 144.
    //
    // The comparison is the CAP and not the charge, which is what admission
    // compares: `ethereum/execution-specs` @ `0cc100eb1`
    // `src/ethereum/forks/cancun/fork.py:485-487` adds
    // `calculate_total_blob_gas(tx) * tx.max_fee_per_blob_gas` to the figure the
    // balance is tested against.
    val refusals = transactions.filter(refusedAs(_, "INSUFFICIENT_ACCOUNT_FUNDS"))
    val decidedByBlobs = refusals.count { fixture =>
      val ceiling = fixture.transaction.maxFeePerBlobGas.getOrElse(BigInt(0))
      val without = requirementWithoutBlobs(fixture)
      balanceOf(fixture) >= without && balanceOf(fixture) < without + blobGasOf(fixture) * ceiling
    }
    assert(
      refusals.length == 144 && decidedByBlobs == 144,
      "of " + refusals.length.toString + " balance refusals, " + decidedByBlobs.toString +
        " are covered without the blob term and refused with it"
    )
  }

  "the fee actually taken" should "differ from the ceiling in most settling cases" in {
    val _ = assume(transactions.nonEmpty)
    // What separates a build that debits the CHARGE from one that debits the
    // CEILING. Both pass the balance check above; only the first reaches the
    // published root. A corpus where the two coincided everywhere would agree
    // with either.
    val settling = transactions.filter(accepted)
    val visible = settling.count(fixture =>
      fixture.transaction.maxFeePerBlobGas.exists(_ != chargeAt(fixture)) && blobGasOf(fixture) > 0
    )
    assert(
      settling.length == 722 && visible == 450,
      "of " + settling.length.toString + " settling cases, " + visible.toString +
        " state a ceiling that differs from their block's charge"
    )
  }

  it should "be charged in every settling case, which is what makes the debit visible at all" in {
    val _ = assume(transactions.nonEmpty)
    // A settling case carrying no blobs would be charged nothing for them, and
    // would agree with a build that never implemented the debit. None of them
    // is such a case.
    val carrying = transactions.count(fixture => accepted(fixture) && blobGasOf(fixture) > 0)
    assert(carrying == 722, "settling cases that actually carry blobs: " + carrying.toString)
  }

  "the charge itself" should "sit at its floor in all but one case, which is this tier's blind spot" in {
    val _ = assume(transactions.nonEmpty)
    // THE LIMIT, stated as a figure. A build that ignored the derivation and
    // used the floor would settle all 722 identically and be caught by exactly
    // one refusal.
    val aboveFloor = transactions.filter(fixture => chargeAt(fixture) > BlobGasPrice.Minimum)
    assert(
      aboveFloor.length == 1 && aboveFloor.forall(fixture => !accepted(fixture)),
      "cases whose block charges above the floor: " + aboveFloor.length.toString + ", of which settling: " +
        aboveFloor.count(accepted).toString
    )
  }

  it should "be the one case a build stuck at the floor would admit" in {
    val _ = assume(transactions.nonEmpty)
    // And it discriminates, which the figure above does not establish on its
    // own: its ceiling is at the floor and its block charges above it, so a
    // build that never moved the charge admits a transaction the corpus
    // refuses. Without this the derivation would be unreached by the whole
    // state tier.
    val reaching = transactions.filter { fixture =>
      chargeAt(fixture) > BlobGasPrice.Minimum &&
      fixture.transaction.maxFeePerBlobGas.exists(ceiling =>
        ceiling < chargeAt(fixture) && ceiling >= BlobGasPrice.Minimum
      )
    }
    assert(
      reaching.length == 1 && reaching.forall(refusedAs(_, "INSUFFICIENT_MAX_FEE_PER_BLOB_GAS")),
      "cases separating the derived charge from its floor: " + reaching.length.toString
    )
  }

  "the versioned-hash refusals" should "include commitments malformed past the first" in {
    val _ = assume(transactions.nonEmpty)
    // What makes the rule a scan rather than a test of the first element. Two of
    // the four carry a well-formed commitment ahead of the malformed one, so a
    // build that stopped at the first admits them.
    val refusals = transactions.filter(refusedAs(_, "TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH"))
    val pastTheFirst = refusals.count { fixture =>
      val carried = fixture.transaction.blobVersionedHashes
      carried.headOption.exists(BlobGas.versionKnown) && carried.exists(hash => !BlobGas.versionKnown(hash))
    }
    assert(
      refusals.length == 4 && pastTheFirst == 1,
      "of " + refusals.length.toString + " versioned-hash refusals, " + pastTheFirst.toString +
        " are well-formed in their first commitment"
    )
  }

  // ── What the two operation directories can discriminate ────────────────────

  "the operation directories" should "reach it from a transaction carrying no commitments" in {
    val _ = assume(hashCost.nonEmpty)
    // The out-of-range answer, which is an ANSWER and not a refusal. A directory
    // whose every case carried commitments could not tell a build that pushes
    // zero past the end from one that halts there.
    val barren = hashCost.count(_.transaction.blobVersionedHashes.isEmpty) +
      hashContexts.count(_.transaction.blobVersionedHashes.isEmpty)
    assert(
      barren == 24,
      "cases running the operation against a transaction carrying nothing: " + barren.toString + " of " +
        (hashCost.length + hashContexts.length).toString
    )
  }

  it should "reach it from a transaction carrying the fork's maximum too" in {
    val _ = assume(hashCost.nonEmpty)
    // The other end, without which every case would be satisfied by a build that
    // answered zero unconditionally.
    val laden = hashCost.count(_.transaction.blobVersionedHashes.length == 6) +
      hashContexts.count(_.transaction.blobVersionedHashes.length == 6)
    assert(
      laden == 15,
      "cases running the operation against a transaction carrying six commitments: " + laden.toString
    )
  }

  // ── The ported directory, and the one case nothing here decides ───────────

  "the ported directory" should "name a blob transaction that deploys, and exactly one" in {
    val _ = assume(ported.nonEmpty)
    // The case the generated directory has no equivalent of. Its recipient is
    // absent where every other blob transaction in the tier names one.
    val deploying =
      ported.count(fixture => fixture.transaction.kind == TransactionType.Blob && fixture.transaction.to.isEmpty)
    assert(
      ported.length == 5 && deploying == 1,
      "of " + ported.length.toString + " ported cases, " + deploying.toString + " state a deploying blob transaction"
    )
  }

  it should "publish signed bytes for it that no conformant decoder reads" in {
    val _ = assume(ported.nonEmpty)
    // WHY THAT CASE IS SKIPPED RATHER THAN DECIDED, asserted so the reason is
    // checked rather than described. The recipient a blob transaction states is
    // an address in this build and in `ethereum/execution-specs` @ `0cc100eb1`
    // alike (`src/ethereum/forks/cancun/transactions.py:305`), so bytes
    // carrying an empty one are not a blob transaction in either -- and the
    // refusal the fixture names is reached by neither.
    //
    // **Pinned so that a corpus refresh publishing readable bytes for it turns
    // a skip into a divergence rather than into silence.**
    val unreadable = ported.filter(fixture =>
      fixture.transaction.to.isEmpty &&
        fixture.transaction.signed.exists(bytes => Transaction.fromCanonicalBytes(bytes.toIArray).isLeft)
    )
    assert(
      unreadable.length == 1,
      "ported cases whose published bytes this build refuses to decode: " + unreadable.length.toString
    )
  }

  it should "leave its other four decodable and decided" in {
    val _ = assume(ported.nonEmpty)
    // The negative control for the assertion above: four of the five publish
    // bytes this build reads, so the one refusal is about that case rather than
    // about the directory.
    val readable = ported.count(fixture =>
      fixture.transaction.signed.forall(bytes => Transaction.fromCanonicalBytes(bytes.toIArray).isRight)
    )
    assert(readable == 4, "ported cases whose published bytes this build reads: " + readable.toString)
  }

  it should "publish no refusal at all, so neither says anything about admission" in {
    val _ = assume(hashCost.nonEmpty)
    // Stated rather than left to be noticed. These two are entirely about a
    // build that runs the operation and gets the wrong answer; every rule this
    // document adds to admission is certified by the directory above and by
    // nothing here.
    val refusals = (hashCost ++ hashContexts).count(!accepted(_))
    assert(refusals == 0, "refusals published by the two operation directories: " + refusals.toString)
  }
