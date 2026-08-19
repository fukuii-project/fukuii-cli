package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.BlockContext

/** The transaction a state fixture asks to be executed, with one combination of
  * its data, gas and value arrays already selected.
  */
final case class StateTransaction(
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes,
    sender: Address,
    kind: TransactionKind
)

/** Which transaction shape a fixture wrote.
  *
  * The two later shapes are named rather than rejected at the reader, because a
  * fixture carrying one is a legitimate test that this fork refuses it: a type
  * the fork predates is an invalid transaction, not an unreadable file.
  */
enum TransactionKind:
  case Legacy
  case WithAccessList
  case WithFeeMarket

/** What a state fixture expects one combination to produce. */
final case class StateExpectation(
    root: Hash,
    logs: Option[Hash],
    state: Option[Map[Address, FixtureAccount]],
    rejected: Boolean
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

  /** The fork whose expectations this layer is certified against. */
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
  def decodeFile(path: String, contents: String): Either[String, FileContents] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object of cases").flatMap { obj =>
          obj.toVector.foldLeft(Right(FileContents(Vector.empty, Vector.empty)): Either[String, FileContents]) {
            case (Left(error), _)          => Left(error)
            case (Right(sofar), (name, c)) =>
              decodeCase(name, c).left.map(path + " " + name + ": " + _).map { built =>
                if built.isEmpty then sofar.copy(withoutExpectation = sofar.withoutExpectation :+ name)
                else sofar.copy(fixtures = sofar.fixtures ++ built)
              }
          }
        }
      }

  def decodeCase(name: String, json: Json): Either[String, Vector[StateFixture]] =
    val cursor = json.hcursor
    for
      envJson <- cursor.downField("env").focus.toRight("no env")
      preJson <- cursor.downField("pre").focus.toRight("no pre")
      txJson <- cursor.downField("transaction").focus.toRight("no transaction")
      block <- blockOf(envJson)
      pre <- FixtureValues.accounts(preJson)
      entries = cursor.downField("post").downField(Fork).values.map(_.toVector).getOrElse(Vector.empty)
      built <- entries.foldLeft(Right(Vector.empty): Either[String, Vector[StateFixture]]) {
        case (Left(error), _)      => Left(error)
        case (Right(sofar), entry) =>
          for
            indexes <- indexesOf(entry)
            transaction <- transactionOf(txJson, indexes)
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
    yield BlockContext(coinbase, number, timestamp, difficulty, gasLimit)

  private def transactionOf(json: Json, indexes: Indexes): Either[String, StateTransaction] =
    val cursor = json.hcursor
    val kind =
      if cursor.downField("maxFeePerGas").focus.isDefined then TransactionKind.WithFeeMarket
      else if cursor.downField("accessLists").focus.isDefined then TransactionKind.WithAccessList
      else TransactionKind.Legacy
    for
      nonce <- FixtureValues.quantityAt(json, "nonce")
      gasPrice <- priceOf(json, kind)
      gasLimit <- selected(json, "gasLimit", indexes.gas).flatMap(FixtureValues.quantity)
      value <- selected(json, "value", indexes.value).flatMap(FixtureValues.quantity)
      data <- selected(json, "data", indexes.data).flatMap(FixtureValues.bytesOf)
      sender <- FixtureValues.addressAt(json, "sender")
      to <- recipientOf(json)
    yield StateTransaction(nonce, gasPrice, gasLimit, to, value, data, sender, kind)

  /** A fee-market transaction states no gas price. It is invalid at this fork
    * whatever it states, so the price it is charged at never matters and zero
    * keeps the reader total rather than making an unreadable field fatal.
    */
  private def priceOf(json: Json, kind: TransactionKind): Either[String, BigInt] =
    if json.hcursor.downField("gasPrice").focus.isDefined then FixtureValues.quantityAt(json, "gasPrice")
    else if kind == TransactionKind.Legacy then Left("no gasPrice on a legacy transaction")
    else Right(BigInt(0))

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

  private def expectationOf(entry: Json): Either[String, StateExpectation] =
    val cursor = entry.hcursor
    val rejected = cursor.downField("expectException").focus.isDefined
    for
      root <- cursor.downField("hash").as[String].left.map(_ => "no hash").flatMap(FixtureValues.hashOf)
      logs <- cursor.downField("logs").as[String] match
        case Left(_)      => Right(None)
        case Right(value) => FixtureValues.hashOf(value).map(Some(_))
      state <- cursor.downField("state").focus match
        case None       => Right(None)
        case Some(json) => FixtureValues.accounts(json).map(Some(_))
    yield StateExpectation(root, logs, state, rejected)
