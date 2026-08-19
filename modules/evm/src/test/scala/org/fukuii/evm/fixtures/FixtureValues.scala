package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.{Word, WorldState}

/** One account as a fixture states it. */
final case class FixtureAccount(nonce: BigInt, balance: BigInt, code: Bytes, storage: Map[BigInt, BigInt])

/** Reading the values a published fixture is written in, and putting them into
  * and back out of a world state.
  *
  * ==Every quantity here is read as a lead, never trusted as well-formed==
  *
  * These files are third-party input. A reader that skips a field it cannot
  * parse turns a corrupt fixture into a passing one, so every conversion
  * returns its failure and the caller counts it as a skip rather than
  * discarding it.
  */
object FixtureValues:

  /** A hex or decimal scalar. Both spellings appear across the two corpora, and
    * an empty string is zero rather than an error: several fixtures write an
    * absent quantity that way.
    */
  def quantity(text: String): Either[String, BigInt] =
    val trimmed = text.trim
    if trimmed.isEmpty then Right(BigInt(0))
    else if trimmed.startsWith("0x") || trimmed.startsWith("0X") then
      val body = trimmed.substring(2)
      if body.isEmpty then Right(BigInt(0))
      else if body.forall(isHexDigit) then Right(BigInt(body, 16))
      else Left("not a hex quantity: " + trimmed)
    else if trimmed.forall(_.isDigit) then Right(BigInt(trimmed))
    else Left("not a quantity: " + trimmed)

  /** A byte string. An odd number of digits is left-padded rather than
    * rejected, because a fixture writes a storage value and a byte string in
    * the same spelling and only one of the two is required to be whole bytes.
    */
  def bytesOf(text: String): Either[String, Bytes] =
    val trimmed = text.trim
    val body = if trimmed.startsWith("0x") || trimmed.startsWith("0X") then trimmed.substring(2) else trimmed
    val padded = if body.length % 2 == 0 then body else "0" + body
    Bytes.fromHex(padded).left.map(error => "not a byte string: " + trimmed + " (" + error + ")")

  def addressOf(text: String): Either[String, Address] =
    bytesOf(text).flatMap { raw =>
      if raw.length == Address.Width then Right(Address.fromBytesTruncating(raw.toIArray))
      else Left("not a 20-byte address: " + text)
    }

  def hashOf(text: String): Either[String, Hash] =
    bytesOf(text).flatMap { raw =>
      if raw.length == Hash.Width then Right(Hash.fromBytesTruncating(raw.toIArray))
      else Left("not a 32-byte hash: " + text)
    }

  def stringAt(json: Json, field: String): Either[String, String] =
    json.hcursor.downField(field).as[String].left.map(_ => "missing or non-string field: " + field)

  def quantityAt(json: Json, field: String): Either[String, BigInt] =
    stringAt(json, field).flatMap(quantity)

  def bytesAt(json: Json, field: String): Either[String, Bytes] =
    stringAt(json, field).flatMap(bytesOf)

  def addressAt(json: Json, field: String): Either[String, Address] =
    stringAt(json, field).flatMap(addressOf)

  /** An `address -> account` map, which is how both corpora write a pre-state
    * and an expected post-state.
    */
  def accounts(json: Json): Either[String, Map[Address, FixtureAccount]] =
    json.asObject.toRight("expected an object of accounts").flatMap { obj =>
      obj.toVector.foldLeft(Right(Map.empty): Either[String, Map[Address, FixtureAccount]]) {
        case (Left(error), _)             => Left(error)
        case (Right(sofar), (key, entry)) =>
          for
            address <- addressOf(key)
            account <- account(entry)
          yield sofar.updated(address, account)
      }
    }

  def account(json: Json): Either[String, FixtureAccount] =
    for
      nonce <- quantityAt(json, "nonce")
      balance <- quantityAt(json, "balance")
      code <- bytesAt(json, "code")
      storage <- storageAt(json)
    yield FixtureAccount(nonce, balance, code, storage)

  private def storageAt(json: Json): Either[String, Map[BigInt, BigInt]] =
    json.hcursor.downField("storage").focus match
      case None        => Right(Map.empty)
      case Some(slots) =>
        slots.asObject.toRight("expected an object of storage slots").flatMap { obj =>
          obj.toVector.foldLeft(Right(Map.empty): Either[String, Map[BigInt, BigInt]]) {
            case (Left(error), _)            => Left(error)
            case (Right(sofar), (key, held)) =>
              for
                slot <- quantity(key)
                value <- held.asString.toRight("storage value is not a string").flatMap(quantity)
              yield sofar.updated(slot, value)
          }
        }

  /** Writes a fixture's accounts into `world`.
    *
    * A slot holding zero is written as a zero rather than skipped: that is the
    * seam's stated contract, and letting the fixture's own omission stand in
    * for it would agree with the implementation for the wrong reason.
    */
  def seed(world: WorldState, accounts: Map[Address, FixtureAccount]): Either[String, Unit] =
    accounts.toVector.sortBy(_._1.toHex).foldLeft(Right(()): Either[String, Unit]) {
      case (Left(error), _)                => Left(error)
      case (Right(()), (address, account)) =>
        UInt64.fromBigInt(account.nonce).left.map(_ => "unrepresentable nonce " + account.nonce).map { nonce =>
          world.touch(address)
          world.setNonce(address, nonce)
          world.setBalance(address, Word(account.balance))
          if account.code.nonEmpty then world.setCode(address, account.code)
          account.storage.foreach((slot, value) => world.setStorage(address, Word(slot), Word(value)))
        }
    }

  /** Every way `world` disagrees with the accounts a fixture expects, over the
    * addresses and slots the fixture names.
    *
    * `slotsOf` supplies the slots to interrogate per address, because a trie
    * answers a point lookup and cannot be enumerated: a slot named in neither
    * the pre-state nor the expected post-state is outside what this can see,
    * and the state-root comparison is what covers that.
    */
  def divergences(
      world: WorldState,
      expected: Map[Address, FixtureAccount],
      slotsOf: Address => Set[BigInt]
  ): Vector[String] =
    expected.toVector.sortBy(_._1.toHex).flatMap { (address, account) =>
      val where = address.toHex
      val nonce =
        Option.when(world.nonceOf(address).toBigInt != account.nonce)(
          where + " nonce " + world.nonceOf(address).toBigInt.toString + " != " + account.nonce.toString
        )
      val balance =
        Option.when(world.balanceOf(address).toBigInt != account.balance)(
          where + " balance " + world.balanceOf(address).toBigInt.toString + " != " + account.balance.toString
        )
      val code =
        Option.when(world.codeOf(address) != account.code)(
          s"$where code ${world.codeOf(address).toHex} != ${account.code.toHex}"
        )
      val slots = (slotsOf(address) ++ account.storage.keySet).toVector.sorted.flatMap { slot =>
        val held = world.storageAt(address, Word(slot)).toBigInt
        val want = account.storage.getOrElse(slot, BigInt(0))
        Option.when(held != want)(
          where + " slot " + slot.toString + " holds " + held.toString + ", expected " + want.toString
        )
      }
      nonce.toVector ++ balance.toVector ++ code.toVector ++ slots
    }

  private def isHexDigit(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
