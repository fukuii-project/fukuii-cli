package org.fukuii.blockchaintests

import io.circe.{ACursor, Json}

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureAccount, FixtureValues}

/** One block a published case states, as the bytes a node would be handed.
  *
  * ==Bytes rather than a decoded block, because decoding is under test==
  *
  * Turning these bytes into a block is this build's codec, which is production
  * code. A reader that decoded them would report a block the codec refuses as a
  * case that did not decode -- a skip -- where the corpus is stating a block a
  * node must read. So the reader carries the bytes and the runner decodes them,
  * and a refusal there is a divergence.
  */
enum StatedBlock:

  /** A block the case expects to be accepted.
    *
    * @param hash
    *   what the case states its header hashes to, which is how the runner shows
    *   that the codec read the header the case published.
    */
  case Valid(rlp: Bytes, hash: Hash)

  /** A block the case expects to be refused, under any of the names it states.
    *
    * @param decodedHash
    *   what the case states the refused block's header hashes to, which it
    *   publishes as `rlp_decoded` beside the encoding. **Absent is a statement,
    *   not a gap**: the release publishes no decoded form where its own tool
    *   could not decode the bytes -- at `tests-v20.0.1`, across every label, each
    *   refused block lacking one names `BlockException.RLP_STRUCTURES_ENCODING`
    *   among its refusals, and every refused block carrying one states a hash
    *   its own encoding reproduces -- so the runner reads the absence as the
    *   case saying the block does not decode.
    */
  case Invalid(rlp: Bytes, rejection: ExpectedRejection, decodedHash: Option[Hash])

/** What a case's `config` states.
  *
  * @param otherKeys
  *   every key beside the two read here, by name. **A key no comparison reads is
  *   not passed over**: the runner diverges on it, so a configuration a later
  *   label adds -- a blob schedule, say -- is compared before any case stating
  *   it can agree.
  */
final case class StatedConfig(network: Option[String], chainId: Option[UInt64], otherKeys: Vector[String])

/** One case of the published `blockchain_tests` tier, as its file states it.
  *
  * @param network
  *   the name of the rules the case was filled under, which is a key into
  *   [[FixtureNetworks]] rather than anything this reader resolves.
  * @param config
  *   what the case's `config` states, absent where the case carries none.
  *   **Absent is not a value**: the older snapshots of the same tier carry no
  *   `config` at all, and the runner takes a network's identifier from the
  *   network rather than filling one in here.
  * @param genesisRlp
  *   the genesis block's encoding, which the runner decodes and hashes.
  * @param genesisHash
  *   what the case states the genesis header hashes to.
  * @param postState
  *   every account the case states the chain ends holding.
  * @param lastBlockHash
  *   the hash of the head the chain ends at, which is the genesis where the case
  *   accepts no block.
  */
final case class BlockchainFixture(
    name: String,
    network: String,
    config: Option[StatedConfig],
    genesisRlp: Bytes,
    genesisHash: Hash,
    pre: Map[Address, FixtureAccount],
    postState: Map[Address, FixtureAccount],
    lastBlockHash: Hash,
    blocks: Vector[StatedBlock]
)

/** One file's cases, and the cases in it this reader could not decode. */
final case class BlockchainFile(fixtures: Vector[BlockchainFixture], undecodable: Vector[(String, String)])

object BlockchainFixture:

  /** Every case in one file.
    *
    * ==A case that does not decode is named, and the rest of its file is read==
    *
    * A file holds many cases, and one malformed case says nothing about its
    * siblings. So an undecodable case is returned beside the decoded ones, to be
    * counted under its own reason, rather than failing the file and dropping
    * every case in it from a report that would then read smaller and still
    * clean.
    */
  def decodeFile(path: String, contents: String): Either[String, BlockchainFile] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object of cases").map { cases =>
          cases.toVector.foldLeft(BlockchainFile(Vector.empty, Vector.empty)) { case (sofar, (name, body)) =>
            decodeCase(name, body) match
              case Right(fixture) => sofar.copy(fixtures = sofar.fixtures :+ fixture)
              case Left(reason)   => sofar.copy(undecodable = sofar.undecodable :+ (name -> reason))
          }
        }
      }

  def decodeCase(name: String, json: Json): Either[String, BlockchainFixture] =
    val cursor = json.hcursor
    for
      network <- FixtureValues.stringAt(json, "network")
      config <- configOf(cursor)
      genesisRlp <- FixtureValues.bytesAt(json, "genesisRLP")
      genesisHash <- hashAt(cursor.downField("genesisBlockHeader"), "genesisBlockHeader.hash", "hash")
      pre <- cursor.downField("pre").focus.toRight("no pre").flatMap(FixtureValues.accounts)
      postState <- postStateOf(cursor)
      lastBlockHash <- hashAt(cursor, "lastblockhash", "lastblockhash")
      blocks <- blocksOf(cursor)
    yield BlockchainFixture(name, network, config, genesisRlp, genesisHash, pre, postState, lastBlockHash, blocks)

  /** The two keys this reader reads, and the name of every other one. */
  private val ReadConfigKeys: Set[String] = Set("network", "chainid")

  private def configOf(cursor: ACursor): Either[String, Option[StatedConfig]] =
    cursor.downField("config").focus match
      case None       => Right(None)
      case Some(json) =>
        json.asObject.toRight("config is not an object").flatMap { obj =>
          for
            network <- FixtureValues.optionally(json, "network")(Right(_))
            chainId <- FixtureValues.optionally(json, "chainid")(FixtureValues.uint64)
          yield Some(StatedConfig(network, chainId, obj.keys.toVector.filterNot(ReadConfigKeys.contains).sorted))
        }

  /** The accounts the chain ends holding.
    *
    * ==A case stating only a root is refused, and says so==
    *
    * The format states exactly one of the two, the accounts or a root over them
    * (`ethereum/execution-specs` @ `0cc100eb1`,
    * `packages/testing/src/execution_testing/fixtures/blockchain.py:111-135`).
    * Every case this runner is calibrated on lists the accounts, so a case
    * stating a root instead is reported as one this reader does not read, rather
    * than as one whose post-state is empty -- which every chain that ends
    * holding something would contradict, and which a comparison would then
    * report as the build's fault.
    */
  private def postStateOf(cursor: ACursor): Either[String, Map[Address, FixtureAccount]] =
    (cursor.downField("postState").focus, cursor.downField("postStateHash").focus) match
      case (Some(accounts), _) => FixtureValues.accounts(accounts)
      case (None, Some(_))     => Left("states postStateHash alone, which this reader does not compare")
      case (None, None)        => Left("no postState")

  private def blocksOf(cursor: ACursor): Either[String, Vector[StatedBlock]] =
    cursor.downField("blocks").focus.flatMap(_.asArray).toRight("no blocks array").flatMap { entries =>
      entries.toVector.zipWithIndex.foldLeft[Either[String, Vector[StatedBlock]]](Right(Vector.empty)) {
        case (carried, (entry, index)) => carried.flatMap(sofar => blockOf(entry, index).map(sofar :+ _))
      }
    }

  /** One block, as a refusal where the case states one and as an acceptance
    * otherwise.
    *
    * ==A refusal keyed some other way is refused here, loudly==
    *
    * `ethereum/legacytests` keys a refusal `expectExceptionALL` or
    * `expectException<Network>` in its older snapshot, and only one of those
    * applies to a given case. A reader looking for `expectException` alone would
    * read such a block as one the case accepts, and the block would then fail
    * for want of a header rather than for the rule the case states. So any other
    * key of that family is refused with its name, which keeps the two kinds of
    * failure apart until a reader that resolves those keys is written.
    */
  private def blockOf(json: Json, index: Int): Either[String, StatedBlock] =
    val cursor = json.hcursor
    val where = "block " + index.toString
    val otherRefusalKeys =
      json.asObject.toVector
        .flatMap(_.keys)
        .filter(key => key.startsWith("expectException") && key != "expectException")
    if otherRefusalKeys.nonEmpty then
      Left(where + " states a refusal keyed " + otherRefusalKeys.mkString(", ") + ", which this reader does not read")
    else
      for
        rlp <- FixtureValues.bytesAt(json, "rlp").left.map(where + ": " + _)
        stated <- cursor.downField("expectException").focus match
          case Some(_) =>
            for
              rejection <- rejectionOf(cursor).left.map(where + ": " + _)
              decodedHash <- decodedHashOf(cursor).left.map(where + ": " + _)
            yield StatedBlock.Invalid(rlp, rejection, decodedHash)
          case None =>
            hashAt(cursor.downField("blockHeader"), where + " blockHeader.hash", "hash")
              .map(hash => StatedBlock.Valid(rlp, hash))
      yield stated

  /** The names a block may be refused under.
    *
    * A case may state several, separated by a bar, where a client is free to
    * refuse for any of them -- the convention the published state tier uses on
    * the same key.
    */
  private def rejectionOf(cursor: ACursor): Either[String, ExpectedRejection] =
    cursor
      .downField("expectException")
      .as[String]
      .left
      .map(_ => "expectException is not a string")
      .map(text => text.split('|').map(_.trim).filter(_.nonEmpty).toSet)
      .filterOrElse(_.nonEmpty, "expectException names nothing")
      .map(ExpectedRejection(_))

  /** The hash of a refused block's decoded header, where the case publishes one.
    *
    * A decoded form stating no header hash is a broken file rather than a block
    * that does not decode, so it is refused here instead of being read as the
    * absence the runner acts on.
    */
  private def decodedHashOf(cursor: ACursor): Either[String, Option[Hash]] =
    cursor.downField("rlp_decoded").focus match
      case None    => Right(None)
      case Some(_) =>
        hashAt(cursor.downField("rlp_decoded").downField("blockHeader"), "rlp_decoded.blockHeader.hash", "hash")
          .map(Some(_))

  private def hashAt(cursor: ACursor, what: String, field: String): Either[String, Hash] =
    cursor.downField(field).as[String].left.map(_ => "no " + what).flatMap(FixtureValues.hashOf)
