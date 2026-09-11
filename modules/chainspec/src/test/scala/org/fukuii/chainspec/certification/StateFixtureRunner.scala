package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.{BlobGas, BlobGasPrice, BlockContext, BlockRandomness, JournaledWorldState, StateTrieWorldState}
import org.fukuii.execution.{
  FeeOffer,
  Admission,
  BlobGasTerms,
  BlobOffer,
  BlockProcessor,
  OfferedTransaction,
  Refusal,
  Settlement,
  TransactionAdmission,
  TransactionProcessor
}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.{Log, Receipt, Transaction, TransactionType}

/** Runs one state fixture: seeds the pre-state, admits the transaction, settles
  * whatever was admitted, and compares the state it reached against the
  * published one.
  *
  * ==Everything a fixture asks for is production code==
  *
  * A fixture states a transaction, and both halves of what a transaction needs
  * -- `org.fukuii.execution.TransactionAdmission` and
  * `org.fukuii.execution.TransactionProcessor` -- are the layer a node runs.
  * Nothing here decides a consensus question. What is left is the harness's
  * own: which files to read, which fork's expectations to read them under, how
  * the corpus's vocabulary for a refusal maps onto this build's, and how a
  * divergence is described.
  *
  * **This replaced a driver that carried the transaction layer itself**, in
  * test scope, and whose own documentation was candid that a result produced
  * through it was evidence about the machine AND about the driver together.
  * That is no longer a caveat any result here carries.
  *
  * ==It lives here because a fixture is read at a NETWORK's rules==
  *
  * The machinery that reads a fixture stays in `evm`'s test tree with the JSON
  * parser and the machine it was written for. Which rules a corpus is run under
  * is a schedule's answer, so the part that resolves and runs sits in this
  * module beside [[CertificationCorpora]].
  */
object StateFixtureRunner:

  /** The corpus's vocabulary for an invalid transaction, against the refusals
    * this build can produce.
    *
    * Only what admission decides appears here. A name absent from this map
    * survives verbatim in [[org.fukuii.evm.fixtures.ExpectedRejection.stated]],
    * so a case naming a rule this build has not implemented diverges and says
    * which one -- rather than passing because a refusal for some other reason
    * left the state root where the fixture expected it.
    *
    * It sits with the runner rather than with the reader because only this side
    * can name a [[org.fukuii.execution.Refusal]] at all: the reader is in a
    * module below the one that holds them, and a reader translating into a
    * vocabulary it cannot see would have to keep a second copy of it.
    */
  private val RefusalVocabulary: Map[String, Refusal] =
    Map(
      "TransactionException.TYPE_1_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_2_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_3_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_4_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.INTRINSIC_GAS_TOO_LOW" -> Refusal.IntrinsicGasTooLow,
      "TransactionException.INITCODE_SIZE_EXCEEDED" -> Refusal.InitcodeTooLarge,
      "TransactionException.NONCE_IS_MAX" -> Refusal.NonceIsMax,
      "TransactionException.GAS_ALLOWANCE_EXCEEDED" -> Refusal.GasAllowanceExceeded,
      "TransactionException.NONCE_MISMATCH_TOO_LOW" -> Refusal.NonceMismatch,
      "TransactionException.NONCE_MISMATCH_TOO_HIGH" -> Refusal.NonceMismatch,
      "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS" -> Refusal.InsufficientAccountFunds,
      "TransactionException.INVALID_SIGNATURE_VRS" -> Refusal.InvalidSignature,
      "TransactionException.SENDER_NOT_EOA" -> Refusal.SenderNotEoa,
      "TransactionException.WRONG_CHAIN_ID" -> Refusal.WrongChainId,
      // The name the corpus actually writes for the rule the entry above was
      // meant to reach: 68 files in the generated tier use this spelling and
      // none uses that one. Both are kept because a key that matches nothing
      // cannot produce a wrong verdict, and which of the two a future corpus
      // writes is the corpus's to decide -- but only this one is doing any work.
      "TransactionException.INVALID_CHAINID" -> Refusal.WrongChainId,
      // A PRE-EXISTING GAP, surfaced by the first corpus to carry a case for it
      // rather than introduced with one. The fee-market rule has been in this
      // build since London and no tier read before this one publishes a
      // transaction refused by it, so the name was never needed -- which is
      // what a corpus that could not disagree looks like from the vocabulary's
      // side.
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_GAS" -> Refusal.FeeCapBelowBaseFee,
      // THE SECOND PRE-EXISTING GAP OF EXACTLY THAT SHAPE, and it is worth
      // stating that the entry above did not predict it. That one was surfaced
      // by a corpus carrying a transaction the fee market refuses for its cap;
      // this one is the market's OTHER refusal -- a tip above the cap -- and
      // the same reasoning would have found it had anyone applied it twice.
      //
      // It became reachable with the re-pinned legacy bulk and with nothing
      // else. Measured across the whole published state tier at this release:
      // five files anywhere state this name, four of them under forks this
      // build's ladder does not reach, and the fifth is
      // `for_cancun/ported_static/stEIP1559/test_tip_too_high`. So no corpus
      // registered before that one could have disagreed about it.
      "TransactionException.PRIORITY_GREATER_THAN_MAX_FEE_PER_GAS" -> Refusal.PriorityFeeAboveFeeCap,
      // The rules the blob format brings. Each name is the corpus's for one of
      // this document's own refusals, and the mapping is what turns a refusal
      // compared by NAME into a refusal compared by RULE -- without it a build
      // refusing for exactly the right reason is reported as diverging, which
      // is how these five arrived.
      "TransactionException.TYPE_3_TX_CONTRACT_CREATION" -> Refusal.FormatMayNotDeploy,
      "TransactionException.TYPE_3_TX_ZERO_BLOBS" -> Refusal.BlobListEmpty,
      "TransactionException.TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH" -> Refusal.BlobHashVersionUnknown,
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_BLOB_GAS" -> Refusal.BlobFeeCapBelowCharge,
      // Two names for one rule, and the corpus states them as ALTERNATIVES on
      // the same case -- `A|B`, which the reader splits and this build satisfies
      // by producing a refusal either maps to. They are kept apart rather than
      // folded because a corpus is free to publish either alone, and a key that
      // matches nothing cannot produce a wrong verdict.
      "TransactionException.TYPE_3_TX_BLOB_COUNT_EXCEEDED" -> Refusal.BlobGasAllowanceExceeded,
      "TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED" -> Refusal.BlobGasAllowanceExceeded
    )

  /** Runs `fixture` as `chainId`'s network at `rules`.
    *
    * ==No default for either, deliberately==
    *
    * This took one network's genesis rules when the caller named none, which
    * made the harness quietly opinionated about which chain a corpus belonged
    * to: a caller that forgot to say certified something other than what it
    * meant to, and nothing reported it. The rules a corpus is read under are
    * part of what the corpus IS, and so is the network asked -- a signature
    * naming a chain identifier is refused or admitted by that answer alone.
    */
  def run(fixture: StateFixture, chainId: UInt64, rules: UpgradeRules): Verdict =
    val trie = VmFixtureRunner.freshTrie()
    val base = new StateTrieWorldState(trie)
    FixtureValues.seed(base, fixture.pre) match
      case Left(error) => Verdict.Skipped(SkipReason.Undecodable(error))
      case Right(())   =>
        executeSeeded(fixture.copy(block = blockUnder(fixture.block, rules)), chainId, rules, trie, base)

  /** The block the fixture describes, holding only the members a block at these
    * rules can carry.
    *
    * ==A stated field is what the GENERATOR wrote, not what the fork has==
    *
    * One corpus here writes both of these on every case whatever fork it
    * publishes an expectation for: every one of the 2617 cases in the published
    * Ethereum Classic tree states a base fee and a randomness value, and **no
    * upgrade on that network adopts a fee market at all** -- so every tier read
    * from it is read under rules whose headers carry neither. The presence of a
    * field is therefore a fact about the generator's one env shape and says
    * nothing about the block; the rules are the only thing that can answer.
    *
    * **Reading the base fee from presence alone moves every root at such a
    * fork**, because what the producer is credited is the price less the
    * block's charge -- so a charge that should not exist is subtracted from
    * every case's beneficiary and every case diverges. The randomness member is
    * inert below the fork that reads it and is narrowed on the same rule
    * anyway, because a block that predates a beacon carries no such value and
    * filling one models a block that never existed.
    */
  private def blockUnder(block: BlockContext, rules: UpgradeRules): BlockContext =
    block.copy(
      baseFee = if rules.header.feeMarket.isDefined then block.baseFee else None,
      prevRandao = if rules.evm.blockRandomness == BlockRandomness.Eip4399 then block.prevRandao else None,
      // The third member to need this, and the one the corpus happens to write
      // consistently -- which is why the narrowing is defensive rather than
      // load-bearing. Measured across the generated tree: 39,930 of its 59,966
      // cases state `currentExcessBlobGas`, and the 20,036 that do not are
      // exactly the cases of the twelve directories where the field appears
      // nowhere at all; the four where it appears state it on every case. So
      // unlike the base fee above, presence here agrees with the rules today. It
      // is narrowed anyway, because agreement between a generator's env shape
      // and a fork's rules is a property of this corpus rather than of the
      // question.
      excessBlobGas = if rules.header.blobSchedule.isDefined then block.excessBlobGas else None
    )

  /** What reading the published signature established.
    *
    * Three outcomes rather than an address or nothing, because they are three
    * different facts and only one of them is an answer about the transaction: a
    * signature this fork refuses refuses the transaction rather than letting it
    * run as whichever account the file happens to name, and a published
    * signature that does not decode establishes nothing at all.
    */
  private enum Signer:
    case Settled(sender: Address)
    case Refused(reason: Refusal)
    case Unreadable(detail: String)

  /** The account that signed, where the corpus publishes what was signed.
    *
    * ==A stated sender is a convenience; a signature is the authority==
    *
    * No transaction carries a sender -- the specification has no such field and
    * derives one -- so where a fixture publishes the signed bytes, those bytes
    * decide.
    *
    * **The legacy corpus publishes no signed bytes for any of its cases**, so
    * its stated sender stands. That is a property of that corpus, uniform
    * across it, rather than a judgment made case by case -- and nothing
    * degrades quietly, because wherever bytes are present they settle the
    * question in both directions.
    *
    * ==`txbytes` is the CANONICAL form, and the codec reads a different one==
    *
    * `org.fukuii.types.Transaction`'s codec encodes a typed transaction as an
    * RLP byte string wrapping `type || rlp(payload)`, because that is what a
    * transaction is as an element of a block body. A fixture publishes the
    * unwrapped form -- the bytes that travel as a whole transaction and that
    * the hash is taken over -- so reading these through the codec parses the
    * leading `0x01` as a one-byte RLP item and reports the rest as trailing.
    *
    * **The two forms agree for a legacy transaction and disagree for every
    * typed one**, which is why this was invisible until a tier whose fork
    * admits a typed format was wired. It surfaced as 297 entries SKIPPED as
    * undecodable rather than as anything failing -- every typed entry of that
    * tier except the one whose tag the fork does not admit, which is refused
    * for its format before its bytes are read at all.
    */
  private def signerOf(transaction: StateTransaction, chainId: UInt64, rules: UpgradeRules): Signer =
    transaction.signed match
      case None        => Signer.Settled(transaction.sender)
      case Some(bytes) =>
        Transaction.fromCanonicalBytes(bytes.toIArray) match
          case Left(error)   => Signer.Unreadable("published signature: " + error)
          case Right(signed) =>
            TransactionAdmission.senderOf(signed, chainId, rules.admission) match
              case Left(reason)   => Signer.Refused(reason)
              case Right(address) => Signer.Settled(address)

  private def executeSeeded(
      fixture: StateFixture,
      chainId: UInt64,
      rules: UpgradeRules,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    // A transaction of a format this network does not carry is refused for its
    // FORMAT, before anything is spent on its signature. Attempting recovery on
    // an envelope that is not the legacy shape would report an unreadable file
    // rather than a refused transaction, turning checked cases into skipped
    // ones -- and a client meeting one on the wire refuses it by format first
    // for the same reason.
    if !TransactionAdmission.admitsFormat(fixture.transaction.kind, rules.admission) then
      judge(fixture, base, trie, rules, Left(Refusal.TypeNotAdmitted))
    else
      signerOf(fixture.transaction, chainId, rules) match
        case Signer.Unreadable(detail) => Verdict.Skipped(SkipReason.Undecodable(detail))
        case Signer.Refused(reason)    => judge(fixture, base, trie, rules, Left(reason))
        case Signer.Settled(sender)    => executeSigned(fixture, sender, chainId, rules, trie, base)

  private def executeSigned(
      fixture: StateFixture,
      sender: Address,
      chainId: UInt64,
      rules: UpgradeRules,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    val journal = new JournaledWorldState(base)
    // A state fixture is one transaction against an otherwise empty block, so
    // what the block has left to give is its whole limit.
    TransactionAdmission.admit(
      offered(fixture.transaction, sender),
      journal,
      fixture.block.gasLimit,
      fixture.block.baseFee,
      blobGasTermsOf(fixture, rules),
      rules.admission,
      rules.evm.schedule,
      rules.evm.maxInitcodeSize
    ) match
      case Admission.Refused(reason)    => judge(fixture, base, trie, rules, Left(reason))
      case Admission.Admitted(admitted) =>
        val settlement = TransactionProcessor.settle(
          admitted,
          journal,
          trie.destroyAccount,
          fixture.block,
          VmFixtureRunner.blockHashOf,
          chainId,
          rules.evm,
          rules.execution
        )
        judge(fixture, base, trie, rules, Right(settlement))

  /** The fee the fixture stated, as the offer admission reads.
    *
    * The two enumerations hold the same distinction on either side of a module
    * boundary, so this is the whole of the translation and it is exhaustive:
    * a shape added to either stops it compiling. What it must never become is a
    * collapse to one case -- a capped offer resolved as a fixed price pays the
    * ceiling where it should pay the tip plus the block's charge, which settles
    * to a root the fixture does not publish.
    */
  private def offerOf(stated: StatedFee): FeeOffer = stated match
    case StatedFee.Fixed(gasPrice)                      => FeeOffer.Fixed(gasPrice)
    case StatedFee.Capped(maxFee, maxPriorityFeePerGas) => FeeOffer.Capped(maxFee, maxPriorityFeePerGas)

  /** The fixture's transaction as the values admission reads.
    *
    * Every quantity crosses unchanged and unnarrowed. A corpus states a nonce, a
    * limit, a price and a value that no fixed-width type always holds, because
    * overflow at each of them is a thing it tests.
    *
    * ==The declaration crosses too, and offering an empty one is loud on some
    * of the entries that carry one and silent on the rest==
    *
    * The charge for a declared account and slot is intrinsic, so it is compared
    * against the transaction's limit before anything runs, and the same field
    * seeds what the transaction may then reach warm. A transaction declaring an
    * address but offered an empty declaration is charged the base and its data
    * alone, spends less than the chain spent, and settles to a different root.
    *
    * **Measured against the generated tier at the first fork admitting the
    * declaring format**: offering an empty declaration there leaves 154 of its
    * 2742 entries disagreeing and the rest agreeing, so the defect is neither
    * total nor invisible. It is not silent on the entries that declare nothing,
    * which is the trivial half; it is silent on entries that DO declare and
    * exhaust their gas limit either way, because such a transaction is charged
    * its whole limit whatever its intrinsic charge was.
    */
  private def offered(transaction: StateTransaction, sender: Address): OfferedTransaction =
    OfferedTransaction(
      transactionType = transaction.kind,
      sender = sender,
      nonce = transaction.nonce,
      fee = offerOf(transaction.fee),
      gasLimit = transaction.gasLimit,
      to = transaction.to,
      value = transaction.value,
      data = transaction.data,
      accessList = transaction.accessList,
      blobs = blobsOf(transaction)
    )

  /** The blob half of what the fixture stated, as admission reads it.
    *
    * ==The FORMAT decides, exactly as it does for the fee==
    *
    * Not which field happens to be present, which is a second reading of the
    * file that can disagree with the first -- [[offerOf]]'s own contract. A
    * blob transaction is the one format that carries these fields, so the match
    * is on the format and is exhaustive: a format added later stops this
    * compiling rather than silently offering nothing.
    *
    * **The refusal this preserves is the one the corpus states.** A case whose
    * `blobVersionedHashes` is present and empty is a blob transaction carrying
    * none, which is refused for that and not for its format; answering `None`
    * here would make it indistinguishable from an ordinary transaction and the
    * case would settle instead.
    *
    * **A ceiling this build cannot find is offered as zero rather than
    * dropped.** A blob transaction must state one and every published case
    * does, so the branch is unreachable over the corpus -- and a zero offered
    * where a file stated nothing is refused by the charge comparison rather
    * than admitted, which is the direction that cannot turn a missing field
    * into a settled transaction.
    */
  private def blobsOf(transaction: StateTransaction): Option[BlobOffer] =
    transaction.kind match
      case TransactionType.Legacy     => None
      case TransactionType.AccessList => None
      case TransactionType.DynamicFee => None
      case TransactionType.Blob       =>
        Some(BlobOffer(transaction.maxFeePerBlobGas.getOrElse(BigInt(0)), transaction.blobVersionedHashes))
      case TransactionType.SetCode => None

  /** What the block charges for blob gas and how much of it it has left.
    *
    * ==A state fixture is one transaction against an otherwise empty block==
    *
    * So what it has left is the fork's whole maximum, exactly as what it has
    * left to give in gas is its whole limit.
    *
    * ==Absent where the fork accounts for no blob gas, and the two absences are
    * one answer==
    *
    * The schedule and the update fraction are written by one component, so a
    * rule set holding one without the other is a configuration nothing here
    * produces. They are read as a pair anyway rather than defaulted: a zero
    * fraction divides by zero rather than pricing anything, and a zero maximum
    * would refuse every blob transaction for its allowance instead of settling
    * it.
    */
  private def blobGasTermsOf(fixture: StateFixture, rules: UpgradeRules): Option[BlobGasTerms] =
    for
      schedule <- rules.header.blobSchedule
      fraction <- rules.evm.blobBaseFeeUpdateFraction
    yield BlobGasTerms(
      charge = BlobGasPrice.at(fixture.block.excessBlobGas.map(_.toBigInt).getOrElse(BigInt(0)), fraction),
      available = schedule.maxBlobs * BlobGas.PerBlob
    )

  /** Every way the state this reached disagrees with the state the fixture
    * publishes.
    *
    * A refused transaction leaves the pre-state exactly as it was, so the root
    * cannot tell one refusal from another and the reason is compared on its
    * own -- in both directions, and by reason. Checking only that SOME refusal
    * occurred is satisfied by any of them.
    */
  private def judge(
      fixture: StateFixture,
      base: StateTrieWorldState,
      trie: StateTrie,
      rules: UpgradeRules,
      outcome: Either[Refusal, Settlement]
  ): Verdict =
    val expected = fixture.expectation
    val root = trie.stateRoot
    val emitted = Keccak256.hash(RlpCodec.encodeTo[Seq[Log]](outcome.fold(_ => Vector.empty[Log], _.logs)))
    val rootDivergence = Option.when(root != expected.root)("state root " + root.toHex + " != " + expected.root.toHex)
    val logDivergence = expected.logs.flatMap { want =>
      Option.when(emitted != want)("logs " + emitted.toHex + " != " + want.toHex)
    }
    val settlement = (outcome.left.toOption, expected.rejection) match
      case (Some(actual), Some(wanted)) if accepted(wanted).contains(actual) => None
      case (Some(actual), Some(wanted))                                      =>
        Some("refused as " + actual + ", but the fixture expects " + wanted.describe)
      case (Some(actual), None) => Some("refused as " + actual + ", but the fixture expects execution")
      case (None, Some(wanted)) => Some("executed, but the fixture expects refusal as " + wanted.describe)
      case (None, None)         => None
    val unbuilt = outcome.toOption.flatMap(_.unbuilt).map("this build cannot run " + _.opcode.toString)
    val accounts = expected.state.toVector.flatMap { wanted =>
      val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
      FixtureValues.divergences(base, wanted, slots)
    }
    val receipt = expected.receipt.zip(outcome.toOption).flatMap { case (published, settled) =>
      receiptDivergence(published, receiptLeftBy(fixture, settled, root, rules))
    }
    val all =
      rootDivergence.toVector ++ logDivergence.toVector ++ settlement.toVector ++ unbuilt.toVector ++
        receipt.toVector ++ accounts
    if all.isEmpty then Verdict.Agreed else Verdict.Diverged(all)

  /** The receipt this transaction leaves, built by the layer a node runs.
    *
    * ==The cumulative figure is this transaction's own, and only because a
    * state fixture is one transaction==
    *
    * A receipt states the gas the BLOCK has used through it, which is the same
    * number as the transaction's own where there is nothing before it. That is
    * a property of this corpus rather than of receipts, and it is why the
    * accumulation across a block is `org.fukuii.execution.BlockProcessorSpec`'s
    * to assert and not reachable here at all.
    *
    * The root passed is the one already taken above, so a fork whose receipts
    * carry a root compares against the same state this verdict compares against
    * -- and a fork whose receipts carry a status never asks for it.
    */
  private def receiptLeftBy(
      fixture: StateFixture,
      settlement: Settlement,
      root: Hash,
      rules: UpgradeRules
  ): Receipt =
    BlockProcessor.receiptFor(fixture.transaction.kind, settlement, settlement.gasUsed, () => root, rules.execution)

  /** How the receipt this build produced differs from the one the fixture
    * published, where it does.
    *
    * ==The octets decide, and the decode is only for the report==
    *
    * What a receipts trie stores is these bytes, so a receipt that encodes
    * differently is a different receipts root whatever its fields say. Comparing
    * the decoded values instead would put this build's own decoder in front of
    * the comparison, where a decoder that dropped a distinction would hide an
    * encoder that dropped the same one.
    *
    * So the verdict is the byte comparison and the wording below is a reading of
    * why -- including the case where every field agrees, which means the two
    * encode one set of values two ways and is a finding in its own right.
    */
  private def receiptDivergence(published: Bytes, built: Receipt): Option[String] =
    Option.when(Bytes.fromIArray(Receipt.canonicalBytes(built)) != published) {
      Receipt.fromCanonicalBytes(published.toIArray) match
        case Left(error)   => "the published receipt does not decode: " + error
        case Right(wanted) =>
          val fields = Vector(
            differing("first field", built.postStateOrStatus, wanted.postStateOrStatus),
            differing("cumulative gas", built.cumulativeGasUsed.toBigInt, wanted.cumulativeGasUsed.toBigInt),
            differing("format", built.transactionType, wanted.transactionType),
            differing("bloom", built.logsBloom.toHex, wanted.logsBloom.toHex),
            Option.when(built.logs != wanted.logs)("its logs")
          ).flatten
          if fields.isEmpty then "receipt: the same values encoded to octets the published receipt does not hold"
          else "receipt: " + fields.mkString("; ")
    }

  private def differing[A](field: String, built: A, wanted: A): Option[String] =
    Option.when(built != wanted)(field + " " + built.toString + " != " + wanted.toString)

  /** Which of the refusals a fixture names this build can actually produce. */
  private def accepted(expectation: ExpectedRejection): Set[Refusal] =
    expectation.stated.flatMap(RefusalVocabulary.get)
