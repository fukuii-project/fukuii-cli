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
    *   publishes as `rlp_decoded` beside the encoding. **Absent means there is no
    *   hash to check, and nothing more**: the refusal is then compared by name
    *   alone. It does not mean the bytes are unreadable. At `tests-v20.0.1`
    *   every refused block lacking a decoded form states
    *   `BlockException.RLP_STRUCTURES_ENCODING` beside a transaction's name, and
    *   its bytes are well-formed RLP whose structure the filling tool's own
    *   types refuse -- so a client that reads the structure and refuses the
    *   block under the other name answers the case as well as one that cannot
    *   read it. `ethereum/legacytests`' `Constantinople` snapshot publishes no
    *   decoded form for any refused block, whatever it refuses it for.
    */
  case Invalid(rlp: Bytes, rejection: ExpectedRejection, decodedHash: Option[Hash])

/** One fork's entry in a case's `config.blobSchedule`, as the case states it.
  *
  * @param otherKeys
  *   every key of the entry beside the three read here, by name, compared as
  *   [[StatedConfig.otherKeys]] is: a key inside the schedule is no less a rule
  *   the case states than a key beside it.
  */
final case class StatedBlobEntry(
    target: Option[BigInt],
    max: Option[BigInt],
    baseFeeUpdateFraction: Option[BigInt],
    otherKeys: Vector[String]
)

/** What a case's `config` states.
  *
  * @param blobSchedule
  *   each fork's blob parameters, keyed by the fork's name as the case writes
  *   it, absent where the case states no schedule.
  * @param otherKeys
  *   every key beside the three read here, by name. **A key no comparison reads is
  *   not passed over**: the runner diverges on it, so a configuration a later
  *   label adds is compared before any case stating it can agree.
  */
final case class StatedConfig(
    network: Option[String],
    chainId: Option[UInt64],
    blobSchedule: Option[Map[String, StatedBlobEntry]],
    otherKeys: Vector[String]
)

/** One case of the published `blockchain_tests` tier, as its file states it.
  *
  * @param network
  *   the name of the rules the case was filled under, which is a key into
  *   [[FixtureNetworks]] rather than anything this reader resolves.
  * @param sealEngine
  *   how the case states its blocks are sealed, absent where it states nothing.
  *   Read as the case writes it and judged by the runner, which runs a case
  *   stating `NoProof` alone.
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
  * @param otherChains
  *   each block, by its position among the case's blocks, that names a chain
  *   other than `default`, beside the name. The older snapshot names every
  *   block's chain, and a block on another chain builds on that chain's own
  *   blocks rather than on the one before it; the runner follows one chain, so
  *   it diverges on a case naming any other rather than running a branch as the
  *   main chain.
  */
final case class BlockchainFixture(
    name: String,
    network: String,
    sealEngine: Option[String],
    config: Option[StatedConfig],
    genesisRlp: Bytes,
    genesisHash: Hash,
    pre: Map[Address, FixtureAccount],
    postState: Map[Address, FixtureAccount],
    lastBlockHash: Hash,
    blocks: Vector[StatedBlock],
    otherChains: Vector[(Int, String)]
)

/** One file's cases, and the cases in it this reader could not decode. */
final case class BlockchainFile(fixtures: Vector[BlockchainFixture], undecodable: Vector[(String, String)])

object BlockchainFixture:

  /** Every case in one file that `keeps` admits.
    *
    * ==A case that does not decode is named, and the rest of its file is read==
    *
    * A file holds many cases, and one malformed case says nothing about its
    * siblings. So an undecodable case is returned beside the decoded ones, to be
    * counted under its own reason, rather than failing the file and dropping
    * every case in it from a report that would then read smaller and still
    * clean.
    *
    * @param keeps
    *   asked of each case as the file writes it, before it is decoded, so a case
    *   that does not decode is still kept or left by what it states -- a label
    *   reading the cases of one network from files holding several keeps a
    *   broken case of its own and none of another's. Every case, by default.
    */
  def decodeFile(path: String, contents: String, keeps: Json => Boolean = _ => true): Either[String, BlockchainFile] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object of cases").map { cases =>
          cases.toVector.filter((_, body) => keeps(body)).foldLeft(BlockchainFile(Vector.empty, Vector.empty)) {
            case (sofar, (name, body)) =>
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
      sealEngine <- FixtureValues.optionally(json, "sealEngine")(Right(_))
      config <- configOf(cursor)
      genesisRlp <- FixtureValues.bytesAt(json, "genesisRLP")
      genesisHash <- hashAt(cursor.downField("genesisBlockHeader"), "genesisBlockHeader.hash", "hash")
      pre <- cursor.downField("pre").focus.toRight("no pre").flatMap(FixtureValues.accounts)
      postState <- postStateOf(cursor)
      lastBlockHash <- hashAt(cursor, "lastblockhash", "lastblockhash")
      blocks <- blocksOf(cursor, network)
      otherChains <- otherChainsOf(cursor)
    yield BlockchainFixture(
      name,
      network,
      sealEngine,
      config,
      genesisRlp,
      genesisHash,
      pre,
      postState,
      lastBlockHash,
      blocks,
      otherChains
    )

  /** The three keys this reader reads, and the name of every other one. */
  private val ReadConfigKeys: Set[String] = Set("network", "chainid", "blobSchedule")

  /** The three keys a blob schedule's entry is read for. */
  private val ReadBlobKeys: Set[String] = Set("target", "max", "baseFeeUpdateFraction")

  private def configOf(cursor: ACursor): Either[String, Option[StatedConfig]] =
    cursor.downField("config").focus match
      case None       => Right(None)
      case Some(json) =>
        json.asObject.toRight("config is not an object").flatMap { obj =>
          for
            network <- FixtureValues.optionally(json, "network")(Right(_))
            chainId <- FixtureValues.optionally(json, "chainid")(FixtureValues.uint64)
            blobSchedule <- blobScheduleOf(cursor.downField("config"))
          yield Some(
            StatedConfig(network, chainId, blobSchedule, obj.keys.toVector.filterNot(ReadConfigKeys.contains).sorted)
          )
        }

  /** Each fork's blob parameters, read without judging which forks a case may
    * name -- that is the runner's comparison, against the rules each resolves
    * to.
    */
  private def blobScheduleOf(config: ACursor): Either[String, Option[Map[String, StatedBlobEntry]]] =
    config.downField("blobSchedule").focus match
      case None           => Right(None)
      case Some(schedule) =>
        schedule.asObject.toRight("config.blobSchedule is not an object").flatMap { forks =>
          forks.toVector
            .foldLeft[Either[String, Map[String, StatedBlobEntry]]](Right(Map.empty)) { case (sofar, (fork, entry)) =>
              sofar.flatMap(read => blobEntryOf(fork, entry).map(parsed => read.updated(fork, parsed)))
            }
            .map(Some(_))
        }

  private def blobEntryOf(fork: String, entry: Json): Either[String, StatedBlobEntry] =
    val where = "config.blobSchedule." + fork
    entry.asObject.toRight(where + " is not an object").flatMap { obj =>
      for
        target <- FixtureValues.optionally(entry, "target")(FixtureValues.quantity).left.map(where + ": " + _)
        max <- FixtureValues.optionally(entry, "max")(FixtureValues.quantity).left.map(where + ": " + _)
        fraction <- FixtureValues
          .optionally(entry, "baseFeeUpdateFraction")(FixtureValues.quantity)
          .left
          .map(where + ": " + _)
      yield StatedBlobEntry(target, max, fraction, obj.keys.toVector.filterNot(ReadBlobKeys.contains).sorted)
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

  /** The chain the older snapshot names for every block of a linear case. */
  private val DefaultChain: String = "default"

  /** Each block naming a chain other than [[DefaultChain]], with that name.
    *
    * The generated tier names no chain, which is read as naming the default.
    */
  private def otherChainsOf(cursor: ACursor): Either[String, Vector[(Int, String)]] =
    cursor.downField("blocks").focus.flatMap(_.asArray).toRight("no blocks array").flatMap { entries =>
      entries.toVector.zipWithIndex.foldLeft[Either[String, Vector[(Int, String)]]](Right(Vector.empty)) {
        case (carried, (entry, index)) =>
          carried.flatMap { sofar =>
            entry.hcursor.downField("chainname").focus match
              case None       => Right(sofar)
              case Some(name) =>
                name.asString
                  .toRight("block " + index.toString + " states a chainname that is not a string")
                  .map(chain => if chain == DefaultChain then sofar else sofar :+ (index -> chain))
          }
      }
    }

  private def blocksOf(cursor: ACursor, network: String): Either[String, Vector[StatedBlock]] =
    cursor.downField("blocks").focus.flatMap(_.asArray).toRight("no blocks array").flatMap { entries =>
      entries.toVector.zipWithIndex.foldLeft[Either[String, Vector[StatedBlock]]](Right(Vector.empty)) {
        case (carried, (entry, index)) => carried.flatMap(sofar => blockOf(entry, index, network).map(sofar :+ _))
      }
    }

  /** The key a refusal is stated under wherever it applies to every network. */
  private val RefusalKey: String = "expectException"

  /** One block, as a refusal where the case states one for its network and as an
    * acceptance otherwise.
    *
    * ==Three keys, and exactly one may apply==
    *
    * The generated tier and `ethereum/legacytests`' `Cancun` snapshot key a
    * refusal `expectException`. The `Constantinople` snapshot keys it
    * `expectExceptionALL`, or `expectException` followed by a network's name,
    * one key per network the file's cases run -- which is how testeth, the tool
    * that filled its `bcInvalidHeaderTest`, reads the pair, taking the key for its
    * own network or the one for
    * all of them (`ethereum/aleth` @ `b120a12c6`,
    * `test/tools/jsontests/BlockChainTests.cpp:950-959`). So a block states a
    * refusal for this case where one of those three names it, and the keys for
    * other networks are statements about other cases in the same file.
    *
    * **A block stating refusals for other networks and none for this one is
    * refused here, loudly**, rather than read as a block the case accepts: such a
    * block carries no header of its own, so it would fail for want of one rather
    * than for anything the case states. So is a block where two of the keys
    * apply, which leaves no single refusal to compare.
    */
  private def blockOf(json: Json, index: Int, network: String): Either[String, StatedBlock] =
    val cursor = json.hcursor
    val where = "block " + index.toString
    val refusalKeys = json.asObject.toVector.flatMap(_.keys).filter(_.startsWith(RefusalKey)).sorted
    val applying =
      refusalKeys.filter(key => key == RefusalKey || key == RefusalKey + "ALL" || key == RefusalKey + network)
    if applying.length > 1 then
      Left(where + " states more than one refusal for " + network + ": " + applying.mkString(", "))
    else if refusalKeys.nonEmpty && applying.isEmpty then
      Left(where + " states refusals keyed " + refusalKeys.mkString(", ") + " and none for " + network)
    else
      for
        rlp <- FixtureValues.bytesAt(json, "rlp").left.map(where + ": " + _)
        stated <- applying.headOption match
          case Some(key) =>
            for
              rejection <- rejectionOf(cursor, key).left.map(where + ": " + _)
              decodedHash <- decodedHashOf(cursor).left.map(where + ": " + _)
            yield StatedBlock.Invalid(rlp, rejection, decodedHash)
          case None =>
            hashAt(cursor.downField("blockHeader"), where + " blockHeader.hash", "hash")
              .map(hash => StatedBlock.Valid(rlp, hash))
      yield stated

  /** The names a block may be refused under, as `key` states them.
    *
    * A case may state several, separated by a bar, where a client is free to
    * refuse for any of them -- the convention the published state tier uses on
    * the same key.
    */
  private def rejectionOf(cursor: ACursor, key: String): Either[String, ExpectedRejection] =
    cursor
      .downField(key)
      .as[String]
      .left
      .map(_ => key + " is not a string")
      .map(text => text.split('|').map(_.trim).filter(_.nonEmpty).toSet)
      .filterOrElse(_.nonEmpty, key + " names nothing")
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
