package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.BlockContext
import org.fukuii.types.{AccessTuple, TransactionType}

/** What a fixture states this transaction will pay, in the shape its format
  * states it in.
  *
  * ==A sum, because the two are different quantities rather than two spellings
  * of one==
  *
  * A format predating the fee market states one price it pays whatever the
  * block charges; a fee-market format states the most it will pay in total and
  * the most it will pay above that charge. Collapsing them loses which was
  * stated, and what was stated is what a refusal is compared against.
  *
  * ==It is the reader's own vocabulary, and it has to be==
  *
  * `org.fukuii.execution.FeeOffer` is the same distinction one module up, where
  * admission can resolve it against a base fee. This module sits below that one
  * and cannot name it, which is the layering
  * [[org.fukuii.evm.fixtures.ExpectedRejection]] already records for a
  * refusal's vocabulary: the reader states what the file said, and whoever runs
  * the case maps it onto what the layer under test accepts.
  */
enum StatedFee:

  /** A format stating one price. */
  case Fixed(gasPrice: BigInt)

  /** A format stating a ceiling and a tip, under EIP-1559's own field names. */
  case Capped(maxFeePerGas: BigInt, maxPriorityFeePerGas: BigInt)

/** The transaction a state fixture asks to be executed, with one combination of
  * its data, gas and value arrays already selected.
  *
  * @param accessList
  *   the accounts and slots this combination declares ahead of running, empty
  *   for a combination that declares none.
  *
  *   **It is read per index, because the field it comes from is indexed.**
  *   `accessLists` is parallel to `data`, so entry `i` belongs to `data[i]` and
  *   a reader taking the whole field would charge one combination for another's
  *   declaration.
  *
  *   Carried as the sequence the file states rather than as a set: the
  *   intrinsic charge prices duplicates and the warm seed built from the same
  *   field does not, so only the sequence supports both.
  * @param kind
  *   which of EIP-2718's formats the fixture wrote. A format a fork predates is
  *   named rather than rejected at the reader, because a fixture carrying one
  *   is a legitimate test that the fork refuses it: an invalid transaction is
  *   not an unreadable file.
  */
final case class StateTransaction(
    nonce: BigInt,
    fee: StatedFee,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes,
    accessList: Seq[AccessTuple],
    sender: Address,
    signed: Option[Bytes],
    kind: TransactionType
)

/** The corpus's own statement that a transaction must be refused, in the
  * corpus's own words.
  *
  * ==The words are kept unmapped, and the mapping lives with the runner==
  *
  * A corpus names rules this build may not have implemented -- which is the
  * state every rule is in before the fork introducing it lands -- so the
  * reader records what the file said and nothing more. Whoever runs the case
  * maps those words onto the refusals this build can produce and reports a case
  * whose stated rule maps to none as a divergence, rather than as no
  * expectation at all.
  */
final case class ExpectedRejection(stated: Set[String]):

  /** The corpus's wording, for a divergence a reader has to act on. */
  def describe: String = stated.toVector.sorted.mkString(" or ")

/** What a state fixture expects one combination to produce.
  *
  * @param receipt
  *   the receipt the fixture published, as the octets a receipts trie stores,
  *   or nothing where it published none.
  *
  *   **Read from the `rlp` member and never from the field that names the
  *   fork's rule**, which is what a reader is drawn to and cannot use. A
  *   receipt object carries seven members and the one stating whether the
  *   transaction succeeded is SIXTH: `transactionHash`, `type`,
  *   `cumulativeGasUsed`, `bloom`, `logs`, then `postState` or `status`, then
  *   `rlp`. Its NAME is the fork's answer, so a reader keyed on it needs a
  *   fork-dependent branch, and the wrong branch asks for a member that is not
  *   there and reports nothing published -- a check that vanishes rather than
  *   fails. `rlp` is spelled the same at every fork, so reading it needs no
  *   branch to get wrong, and it holds the same statement plus the other three
  *   fields.
  *
  *   Measured over the generated tier at the `tests@v20.0.1` release,
  *   2026-08-27, across the four directories this build reads and the one
  *   filled for the fork that replaces the field: that member order holds for
  *   every receipt in all five -- 524, 535, 526 and 526 filled for Frontier
  *   through Spurious Dragon, and 1834 for Byzantium. The four earlier ones
  *   state `postState` and the last states `status`, and no receipt in any of
  *   them states both. So a reader branching on the name would be right in one
  *   half of the corpus and silent in the other, which is the shape that
  *   reports nothing rather than failing.
  *
  *   **The legacy tier publishes no receipt at all**, so this is empty for
  *   every case in it: all 31,291 post entries across its 2,394 files carry
  *   exactly `hash`, `indexes` and `logs`. Agreement there is not evidence
  *   about a receipt.
  */
final case class StateExpectation(
    root: Hash,
    logs: Option[Hash],
    state: Option[Map[Address, FixtureAccount]],
    rejection: Option[ExpectedRejection],
    receipt: Option[Bytes]
)

/** One executable combination of a state fixture: a name, a pre-state, one
  * transaction and the expectation for it.
  */
final case class StateFixture(
    name: String,
    block: BlockContext,
    pre: Map[Address, FixtureAccount],
    transaction: StateTransaction,
    expectation: StateExpectation
)

/** One file's executable combinations, and the cases inside it that stated no
  * expectation for the fork under test.
  */
final case class FileContents(fixtures: Vector[StateFixture], withoutExpectation: Vector[String])

object StateFixture:

  /** The fork a file is read for when the caller names none.
    *
    * A fixture states its expectations under a fork's name, and a file carrying
    * none for the fork asked about yields cases counted as having no expectation
    * rather than cases that passed. **So this is a reader's parameter, not a
    * property of the corpus** -- the same file answers differently depending on
    * which fork it is asked about, and the generated corpus is filled per fork.
    */
  val Fork: String = "Frontier"

  /** Every executable combination in one file, and the cases that stated no
    * expectation for this fork.
    *
    * A case carries a `post` section per fork, and each section carries one
    * entry per combination of the transaction's arrays, so one file commonly
    * yields several runs. A case with no section for this fork yields none and
    * is named here instead, because a case that quietly disappears between the
    * corpus and the report is coverage nobody can audit.
    */
  def decodeFile(path: String, contents: String, fork: String = Fork): Either[String, FileContents] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object of cases").flatMap { obj =>
          obj.toVector.foldLeft(Right(FileContents(Vector.empty, Vector.empty)): Either[String, FileContents]) {
            case (Left(error), _)          => Left(error)
            case (Right(sofar), (name, c)) =>
              decodeCase(name, c, fork).left.map(path + " " + name + ": " + _).map { built =>
                if built.isEmpty then sofar.copy(withoutExpectation = sofar.withoutExpectation :+ name)
                else sofar.copy(fixtures = sofar.fixtures ++ built)
              }
          }
        }
      }

  def decodeCase(name: String, json: Json, fork: String = Fork): Either[String, Vector[StateFixture]] =
    val cursor = json.hcursor
    for
      envJson <- cursor.downField("env").focus.toRight("no env")
      preJson <- cursor.downField("pre").focus.toRight("no pre")
      txJson <- cursor.downField("transaction").focus.toRight("no transaction")
      block <- blockOf(envJson)
      pre <- FixtureValues.accounts(preJson)
      entries = cursor.downField("post").downField(fork).values.map(_.toVector).getOrElse(Vector.empty)
      built <- entries.foldLeft(Right(Vector.empty): Either[String, Vector[StateFixture]]) {
        case (Left(error), _)      => Left(error)
        case (Right(sofar), entry) =>
          for
            indexes <- indexesOf(entry)
            transaction <- transactionOf(txJson, entry, indexes)
            expectation <- expectationOf(entry)
          yield sofar :+ StateFixture(name + "[" + indexes.label + "]", block, pre, transaction, expectation)
      }
    yield built

  /** Which entry of each of the transaction's arrays this combination selects. */
  final case class Indexes(data: Int, gas: Int, value: Int):
    def label: String = "d" + data + "g" + gas + "v" + value

  private def indexesOf(entry: Json): Either[String, Indexes] =
    val cursor = entry.hcursor.downField("indexes")
    for
      data <- cursor.downField("data").as[Int].left.map(_ => "no indexes.data")
      gas <- cursor.downField("gas").as[Int].left.map(_ => "no indexes.gas")
      value <- cursor.downField("value").as[Int].left.map(_ => "no indexes.value")
    yield Indexes(data, gas, value)

  private def blockOf(json: Json): Either[String, BlockContext] =
    for
      coinbase <- FixtureValues.addressAt(json, "currentCoinbase")
      number <- FixtureValues.quantityAt(json, "currentNumber")
      timestamp <- FixtureValues.quantityAt(json, "currentTimestamp")
      difficulty <- FixtureValues.quantityAt(json, "currentDifficulty")
      gasLimit <- FixtureValues.quantityAt(json, "currentGasLimit")
      // All three are read where the file states them and left absent where it
      // does not, which is what a directory filled for a fork below any of them
      // publishes. The machine refuses to substitute a zero for any -- zero is a
      // legal charge, a legal randomness value and a legal excess, so a default
      // would be a plausible answer for a block that supplied none.
      baseFee <- FixtureValues.optionally(json, "currentBaseFee")(FixtureValues.quantity)
      prevRandao <- FixtureValues.optionally(json, "currentRandom")(FixtureValues.hashOf)
      // Stated by every case of every fork from the one that introduced it, and
      // read by nothing until an operation existed to ask. **Modelling it is not
      // additive for a corpus already certified**: a field a reader ignores
      // cannot move a root, and a field it fills can -- so every corpus that
      // states it is re-measured rather than assumed unaffected.
      excessBlobGas <- FixtureValues.optionally(json, "currentExcessBlobGas")(FixtureValues.uint64)
    yield BlockContext(coinbase, number, timestamp, difficulty, gasLimit, baseFee, prevRandao, excessBlobGas)

  /** The signed transaction a combination was built from, where the corpus
    * publishes it.
    *
    * It sits on the post entry rather than beside the transaction, because each
    * combination of the data, gas and value arrays is signed separately and so
    * has bytes of its own. The legacy corpus publishes none for any case.
    */
  private def signedBytesOf(entry: Json): Either[String, Option[Bytes]] =
    entry.hcursor.downField("txbytes").as[String] match
      case Left(_)     => Right(None)
      case Right(text) => FixtureValues.bytesOf(text).map(Some(_))

  private def transactionOf(json: Json, entry: Json, indexes: Indexes): Either[String, StateTransaction] =
    for
      // Absent is a fact about the corpus; present-and-unreadable is a broken
      // fixture. Folding the second into the first would answer with the stated
      // sender and report nothing, which is the one outcome this must not have.
      signed <- signedBytesOf(entry)
      kind = kindOf(json, signed, indexes)
      nonce <- FixtureValues.quantityAt(json, "nonce")
      fee <- feeOf(json, kind)
      gasLimit <- selected(json, "gasLimit", indexes.gas).flatMap(FixtureValues.quantity)
      value <- selected(json, "value", indexes.value).flatMap(FixtureValues.quantity)
      data <- selected(json, "data", indexes.data).flatMap(FixtureValues.bytesOf)
      declared <- declarationAt(json, indexes.data)
      sender <- FixtureValues.addressAt(json, "sender")
      to <- recipientOf(json)
    yield StateTransaction(nonce, fee, gasLimit, to, value, data, declared, sender, signed, kind)

  /** What `accessLists` declares for one combination.
    *
    * ==Read from the fields rather than from the published envelope==
    *
    * The two production clients that consume this corpus both build the
    * transaction from this field at this index: `besu-eth/besu` @ `fdf1247c6d`
    * takes `accessLists.get(indexes.data)` in
    * `StateTestVersionedTransaction.get`, and `NethermindEth/nethermind` @
    * `b92e2a4719` takes `transactionJson.AccessLists[postStateJson.Indexes.Data]`
    * in `JsonToEthereumTest`. besu reads no published envelope at all -- it
    * re-signs from the secret key -- and nethermind decodes one only for a case
    * expecting an exception.
    *
    * **The two sources were compared before this was written, and they agree.**
    * Over the 154 admitted type-1 entries of the generated tier's Berlin
    * directory, the tuples this field states at each entry's own data index are
    * the tuples that entry's `txbytes` carries, in the same order, in every
    * case. So this corpus cannot discriminate the two readings, and what
    * selects the field is the clients rather than a measurement -- 129 of those
    * entries declare something and 25 declare nothing.
    *
    * ==Absent, null and empty are three different statements==
    *
    * A case stating no `accessLists` at all declares nothing, which is every
    * case at every fork below the one that introduced the field. A null entry
    * is that index saying it predates the envelope. `[]` is a typed transaction
    * listing nothing, and separating it from null is what [[listedAt]] is for.
    *
    * **No case in any corpus this build reads carries a null entry**, so the
    * branch above is asserted by this module's own suite rather than by a run.
    * The shape is real all the same: `etclabscore/tests-etc` writes it eight
    * times, in directories outside the ten this build registers, and
    * `ethereum/legacytests` writes it in a snapshot this build does not read.
    *
    * **An index the field does not reach is a broken fixture and not a third
    * kind of absence.** `accessLists` is parallel to `data`, so a combination
    * whose index falls outside it describes a file whose two arrays disagree,
    * and answering with an empty declaration there would charge a transaction
    * for less than it declares and settle it to a root the chain never reached.
    * No case in any corpus read here does it.
    */
  private def declarationAt(json: Json, index: Int): Either[String, Seq[AccessTuple]] =
    json.hcursor.downField("accessLists").focus match
      case None       => Right(Seq.empty)
      case Some(held) =>
        held.asArray.toRight("accessLists is not an array").flatMap { lists =>
          lists.lift(index).toRight("accessLists has no entry " + index).flatMap { entry =>
            if entry.isNull then Right(Seq.empty)
            else
              entry.asArray.toRight("accessLists entry " + index + " is not an array").flatMap { tuples =>
                tuples.foldLeft(Right(Vector.empty): Either[String, Vector[AccessTuple]]) {
                  case (Left(error), _)      => Left(error)
                  case (Right(sofar), tuple) => declaredTuple(tuple).map(sofar :+ _)
                }
              }
          }
        }

  /** One declared account and the slots declared with it.
    *
    * A tuple stating no `storageKeys` declares the account alone, which the
    * proposal prices and both clients above accept.
    */
  private def declaredTuple(json: Json): Either[String, AccessTuple] =
    for
      address <- FixtureValues.addressAt(json, "address")
      keys <- json.hcursor.downField("storageKeys").focus match
        case None       => Right(Vector.empty)
        case Some(held) =>
          held.asArray.toRight("storageKeys is not an array").flatMap { stated =>
            stated.foldLeft(Right(Vector.empty): Either[String, Vector[Hash]]) {
              case (Left(error), _)     => Left(error)
              case (Right(sofar), slot) =>
                slot.asString
                  .toRight("a declared storage key is not a string")
                  .flatMap(FixtureValues.hashOf)
                  .map(sofar :+ _)
            }
          }
    yield AccessTuple(address, keys)

  /** Which of EIP-2718's formats this one combination is.
    *
    * ==The envelope names the format; the fields only imply it==
    *
    * Where the corpus publishes the signed bytes they carry the format
    * themselves, so nothing has to be inferred from which fields a file
    * happened to write -- and the format named here cannot then disagree with
    * the transaction recovered from those same bytes. The fields answer for a
    * case that publishes no bytes, and for one whose bytes begin with something
    * that names no format at all -- [[envelopeKind]] declines on those, and the
    * `getOrElse` below is what they fall through to.
    */
  private def kindOf(json: Json, signed: Option[Bytes], indexes: Indexes): TransactionType =
    signed.flatMap(envelopeKind).getOrElse(impliedKind(json, indexes))

  /** The lowest leading byte that begins an RLP sequence, and so the shape that
    * predates the envelope.
    */
  private val SequenceHead: Int = 0xc0

  /** The format published bytes are in, where their leading byte names one.
    *
    * A byte that is neither a sequence head nor a tag a proposal has assigned
    * names no format -- an RLP string head, or an unassigned tag -- so this
    * declines rather than reading a format out of it. A leading zero is among
    * them: [[TransactionType.Legacy]]'s number is not a tag any proposal
    * assigns, so bytes beginning `0x00` are malformed rather than legacy.
    *
    * ==Declining here is not the case being left unresolved==
    *
    * [[kindOf]] falls back to the fields, so a case whose bytes name no format
    * still gets one -- and where nothing in the object implies a later format,
    * that one is [[TransactionType.Legacy]]. What declining buys is that the
    * malformed bytes are never the thing a format is read out of; what settles
    * them is the decode in
    * `org.fukuii.chainspec.certification.StateFixtureRunner.signerOf`, which
    * reports an unreadable published signature. No case in either corpus
    * publishes such bytes, so this branch is unreached today.
    */
  private def envelopeKind(bytes: Bytes): Option[TransactionType] =
    bytes.toIArray.headOption.map(_ & 0xff).flatMap { head =>
      if head >= SequenceHead then Some(TransactionType.Legacy)
      else if head == TransactionType.Legacy.number then None
      else TransactionType.fromNumber(head)
    }

  /** The format the transaction object's own fields imply, for a case that
    * publishes no signed bytes.
    *
    * ==Per index, because the field that names the envelope is indexed too==
    *
    * `accessLists` is an array parallel to `data`: entry `i` belongs to
    * `data[i]`, and a null entry is that index saying it is the shape predating
    * the envelope. Reading the field's PRESENCE instead names every index of
    * such a file typed -- including the legacy ones, which at a fork before
    * EIP-2930 refuses a control the fixture expects to execute. `[]` is not
    * null: an empty access list is a typed transaction listing nothing, and the
    * two are what separate the envelope being refused from the list being
    * empty.
    *
    * None of the other three is parallel to `data`, which is why only this one
    * is subscripted: `maxFeePerGas` is one value for the whole case, and the
    * two lists are the transaction's own contents rather than a choice per
    * combination.
    *
    * ==The order is the field's, not the reader's==
    *
    * A transaction of a later format carries the earlier formats' fields as
    * well -- a blob transaction states `maxFeePerGas`, a fee-market one may
    * state an access list -- so the most recent format that fits wins. That
    * ordering is `Transaction.Builder.guessType` in `besu-eth/besu` @
    * `c2addd94`, and the same order arrives as a chain of overrides in
    * `NethermindEth/nethermind` @ `c35ce1b1`
    * `src/Nethermind/Ethereum.Test.Base/JsonToEthereumTest.cs`.
    */
  private def impliedKind(json: Json, indexes: Indexes): TransactionType =
    val cursor = json.hcursor
    def states(field: String): Boolean = cursor.downField(field).focus.isDefined
    if states("authorizationList") then TransactionType.SetCode
    else if states("blobVersionedHashes") then TransactionType.Blob
    else if states("maxFeePerGas") || states("maxPriorityFeePerGas") then TransactionType.DynamicFee
    else if listedAt(json, indexes.data) then TransactionType.AccessList
    else TransactionType.Legacy

  /** Whether `accessLists` carries a non-null entry for this combination. */
  private def listedAt(json: Json, index: Int): Boolean =
    json.hcursor
      .downField("accessLists")
      .focus
      .flatMap(_.asArray)
      .flatMap(_.lift(index))
      .exists(!_.isNull)

  /** What the format decided above states it will pay.
    *
    * ==The FORMAT decides which shape is required==
    *
    * Not which field happens to be present, which is a second reading of the
    * same file that can disagree with the first. The match is exhaustive over
    * [[org.fukuii.types.TransactionType]], so a format added later stops this
    * compiling rather than silently falling into the shape below it. Within the
    * fee-market arm the fields are consulted again, for the one reason the
    * third section below gives.
    *
    * ==A missing field is a decode failure and not a zero==
    *
    * A format that must state a fee and does not is a malformed fixture, which
    * this reports as a skip a report can count. Standing a zero in would settle
    * the case against a price nothing published -- and below a fee market that
    * price is what the sender is charged and the producer credited, so the run
    * would agree or disagree for a reason no reader could see.
    *
    * ==A fee-market FORMAT stating no fee-market field states a price==
    *
    * The format can come from a published tag while the fee can only come from
    * the fields, so the two are not always answered by the same part of the
    * file. Where the fields name neither ceiling nor tip, what the case states
    * is a price and that is what is recorded -- reporting the case undecodable
    * instead would drop a combination whose format is exactly what it was
    * written to settle. Where either fee-market field IS stated, both are
    * required: a ceiling without a tip is a fee-market transaction the
    * specification does not describe.
    */
  private def feeOf(json: Json, kind: TransactionType): Either[String, StatedFee] =
    kind match
      case TransactionType.Legacy | TransactionType.AccessList =>
        FixtureValues.quantityAt(json, "gasPrice").map(StatedFee.Fixed(_))
      case TransactionType.DynamicFee | TransactionType.Blob | TransactionType.SetCode =>
        val cursor = json.hcursor
        val marketed =
          cursor.downField("maxFeePerGas").focus.isDefined ||
            cursor.downField("maxPriorityFeePerGas").focus.isDefined
        if !marketed then FixtureValues.quantityAt(json, "gasPrice").map(StatedFee.Fixed(_))
        else
          for
            cap <- FixtureValues.quantityAt(json, "maxFeePerGas")
            tip <- FixtureValues.quantityAt(json, "maxPriorityFeePerGas")
          yield StatedFee.Capped(cap, tip)

  private def recipientOf(json: Json): Either[String, Option[Address]] =
    FixtureValues.stringAt(json, "to").flatMap { text =>
      val trimmed = text.trim
      if trimmed.isEmpty || trimmed == "0x" then Right(None)
      else FixtureValues.addressOf(trimmed).map(Some(_))
    }

  private def selected(json: Json, field: String, index: Int): Either[String, String] =
    json.hcursor.downField(field).focus.toRight("no " + field).flatMap { held =>
      held.asArray match
        case Some(values) =>
          values.lift(index).toRight(field + " has no entry " + index).flatMap {
            _.asString.toRight(field + " entry " + index + " is not a string")
          }
        case None => held.asString.toRight(field + " is neither an array nor a string")
    }

  /** A fixture may name more than one acceptable reason, separated by a bar,
    * where a client is free to refuse for either.
    */
  private def rejectionOf(entry: Json): Option[ExpectedRejection] =
    entry.hcursor.downField("expectException").as[String].toOption.map { text =>
      ExpectedRejection(text.split('|').map(_.trim).filter(_.nonEmpty).toSet)
    }

  /** The octets of the receipt a combination publishes, where it publishes one.
    *
    * ==Absent and unreadable are kept apart, as they are for a signature==
    *
    * A fixture that states no receipt is a fact about the corpus. A receipt
    * object with no `rlp` in it is a broken file, and folding the second into
    * the first would drop the comparison silently on exactly the fixture whose
    * shape had changed.
    *
    * Across the five generated directories read for this, a receipt is present
    * on every entry expecting execution and on no entry naming an exception --
    * so its absence carries the same information as `expectException` and is
    * not a second thing to check.
    */
  private def receiptOf(entry: Json): Either[String, Option[Bytes]] =
    entry.hcursor.downField("receipt").focus match
      case None       => Right(None)
      case Some(json) =>
        json.hcursor
          .downField("rlp")
          .as[String]
          .left
          .map(_ => "a published receipt states no rlp")
          .flatMap(FixtureValues.bytesOf)
          .map(Some(_))

  private def expectationOf(entry: Json): Either[String, StateExpectation] =
    val cursor = entry.hcursor
    val rejection = rejectionOf(entry)
    for
      root <- cursor.downField("hash").as[String].left.map(_ => "no hash").flatMap(FixtureValues.hashOf)
      logs <- cursor.downField("logs").as[String] match
        case Left(_)      => Right(None)
        case Right(value) => FixtureValues.hashOf(value).map(Some(_))
      state <- cursor.downField("state").focus match
        case None       => Right(None)
        case Some(json) => FixtureValues.accounts(json).map(Some(_))
      receipt <- receiptOf(entry)
    yield StateExpectation(root, logs, state, rejection, receipt)
