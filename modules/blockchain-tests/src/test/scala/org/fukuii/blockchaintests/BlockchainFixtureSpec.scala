package org.fukuii.blockchaintests

import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Hash, UInt64}
import org.fukuii.evm.fixtures.ExpectedRejection

/** What the reader makes of a published block-tier case.
  *
  * ==Why the reader is tested apart from the runner==
  *
  * A reader that drops a refusal reads a block the case refuses as one it
  * accepts, and the runner then disagrees with the corpus for a reason that is
  * the reader's -- which reads, from the report, exactly like the validator
  * being wrong. The cases below are what keep those two apart.
  *
  * Each published case is [[PublishedParisCases]]'s; the variants are those same
  * cases with one member changed, so every shape is read out of material the
  * release wrote rather than out of a file written here.
  */
class BlockchainFixtureSpec extends AnyFlatSpec:

  private def published(name: String): Json =
    PublishedParisCases.json(name).fold(error => fail(error), identity)

  private def decoded(name: String, json: Json): BlockchainFixture =
    BlockchainFixture.decodeCase(name, json).fold(error => fail(name + ": " + error), identity)

  private def hash(hex: String): Hash = Hash.fromHex(hex).fold(error => fail(error.toString), identity)

  /** A case with one block replaced by `edit` of it. */
  private def withBlock(json: Json, index: Int)(edit: Json => Json): Json =
    json.mapObject(obj =>
      obj.add(
        "blocks",
        Json.fromValues(obj("blocks").flatMap(_.asArray).getOrElse(Vector.empty).zipWithIndex.map { case (block, at) =>
          if at == index then edit(block) else block
        })
      )
    )

  private def refusal(json: Json, stated: String): Json =
    withBlock(json, 0)(_.mapObject(_.add("expectException", Json.fromString(stated))))

  /** The BLOCKHASH case with `edit` applied to its `config` object. */
  private def withConfig(edit: JsonObject => JsonObject): Json =
    published(PublishedParisCases.BlockHashCase).mapObject(obj =>
      obj.add("config", obj("config").fold(Json.obj())(_.mapObject(edit)))
    )

  /** A blob schedule's entry as the published `for_cancun` cases state it. */
  private val cancunEntry: Json =
    Json.obj(
      "target" -> Json.fromString("0x03"),
      "max" -> Json.fromString("0x06"),
      "baseFeeUpdateFraction" -> Json.fromString("0x32f0ed")
    )

  "decodeFile" should "read every case the published resource holds and leave none undecodable" in
    assert(
      PublishedParisCases.text
        .flatMap(BlockchainFixture.decodeFile("published-paris-cases.json", _))
        .exists(file => file.fixtures.length == 3 && file.undecodable.isEmpty),
      "the resource holds three published cases, each of which the reader must decode"
    )

  it should "name a case that does not decode and read the rest of its file" in {
    val broken = published(PublishedParisCases.GasLimitCase).mapObject(_.remove("lastblockhash"))
    val file = Json.obj(
      PublishedParisCases.GasLimitCase -> broken,
      PublishedParisCases.SignatureCase -> published(PublishedParisCases.SignatureCase)
    )
    val read = BlockchainFixture.decodeFile("two-cases.json", file.noSpaces)
    assert(
      read.exists(result =>
        result.fixtures.map(_.name) == Vector(PublishedParisCases.SignatureCase) &&
          result.undecodable.map(_._1) == Vector(PublishedParisCases.GasLimitCase)
      ),
      "one malformed case must not drop its sibling from the report: " + read.toString
    )
  }

  "decodeCase" should "read the network a case names" in {
    val network = decoded(PublishedParisCases.BlockHashCase, published(PublishedParisCases.BlockHashCase)).network
    assert(network == "Paris", "the published case names Paris: " + network)
  }

  it should "read the network and chain id a case's config states, and name no other key" in {
    val config = decoded(PublishedParisCases.BlockHashCase, published(PublishedParisCases.BlockHashCase)).config
    assert(
      config == Some(StatedConfig(Some("Paris"), Some(UInt64.fromBits(1L)), None, Vector.empty)),
      "the published config states exactly a network and a chain id: " + config.toString
    )
  }

  it should "name every config key beside the three it reads" in {
    val otherKeys = decoded("keyed", withConfig(_.add("NOT_A_PUBLISHED_KEY", Json.obj()))).config.map(_.otherKeys)
    assert(
      otherKeys == Some(Vector("NOT_A_PUBLISHED_KEY")),
      "a key no comparison reads must reach the runner by name: " + otherKeys.toString
    )
  }

  it should "read each fork's entry of a blob schedule, as the published entry states it" in {
    val schedule =
      decoded("scheduled", withConfig(_.add("blobSchedule", Json.obj("Cancun" -> cancunEntry)))).config
        .flatMap(_.blobSchedule)
    assert(
      schedule == Some(
        Map("Cancun" -> StatedBlobEntry(Some(BigInt(3)), Some(BigInt(6)), Some(BigInt(3338477)), Vector.empty))
      ),
      "the three members a published entry states, as quantities: " + schedule.toString
    )
  }

  it should "name every key inside a blob schedule's entry beside the three it reads" in {
    val extended = cancunEntry.mapObject(_.add("NOT_A_PUBLISHED_KEY", Json.fromString("0x01")))
    val keys =
      decoded("extended", withConfig(_.add("blobSchedule", Json.obj("Cancun" -> extended)))).config
        .flatMap(_.blobSchedule)
        .map(_.get("Cancun").map(_.otherKeys))
    assert(
      keys == Some(Some(Vector("NOT_A_PUBLISHED_KEY"))),
      "a key inside an entry is a rule the case states as much as one beside it: " + keys.toString
    )
  }

  it should "leave the config absent where a case carries none" in {
    val config =
      decoded(
        PublishedParisCases.BlockHashCase,
        published(PublishedParisCases.BlockHashCase).mapObject(_.remove("config"))
      ).config
    assert(config.isEmpty, "a case with no config states no identifier, and the reader must not fill one in")
  }

  it should "read each block the case accepts with the hash its header states" in {
    val blocks = decoded(PublishedParisCases.BlockHashCase, published(PublishedParisCases.BlockHashCase)).blocks
    val statedHashes = blocks.collect { case StatedBlock.Valid(_, stated) => stated }
    assert(
      statedHashes == Vector(
        hash("0xd95ffa736beb74dc847ce0fc182950d6c8908d6ff1c27163c6512307c8287698"),
        hash("0x337cbf7279b6db68e663a30de331d99cd91b7197f70a5fbd93c9e050d874ac2c")
      ),
      "the two blocks and their stated hashes, in the order the case states them: " + statedHashes.toString
    )
  }

  it should "read the block a case refuses with the name it states and the decoded hash it publishes" in {
    val refused =
      decoded(PublishedParisCases.GasLimitCase, published(PublishedParisCases.GasLimitCase)).blocks.collect {
        case StatedBlock.Invalid(_, rejection, decodedHash) => (rejection, decodedHash)
      }
    assert(
      refused == Vector(
        (
          ExpectedRejection(Set("BlockException.INVALID_GASLIMIT")),
          Some(hash("0x7f7edbb97aaa5f1f01186197927068096a83240a9cd809ae75d50e07dbf03975"))
        )
      ),
      "the one refused block, its name and the hash its rlp_decoded header states: " + refused.toString
    )
  }

  it should "read a refused block publishing no decoded form as stating no decoded hash" in {
    val undecoded = withBlock(published(PublishedParisCases.GasLimitCase), 0)(_.mapObject(_.remove("rlp_decoded")))
    val hashes = decoded("undecoded", undecoded).blocks.collect { case StatedBlock.Invalid(_, _, decodedHash) =>
      decodedHash
    }
    assert(
      hashes == Vector(None),
      "an absent decoded form is the case stating no hash to check: " + hashes.toString
    )
  }

  it should "read a refused block keyed as the legacy snapshot keys one" in {
    // `ethereum/legacytests`' snapshot states a refused block with a block
    // number and a chain name beside the refusal, the encoding and the decoded
    // form, where the generated tier states the last three.
    val legacy = withBlock(published(PublishedParisCases.GasLimitCase), 0)(
      _.mapObject(_.add("blocknumber", Json.fromString("1")).add("chainname", Json.fromString("default")))
    )
    val refused = decoded("legacy", legacy.mapObject(_.remove("config"))).blocks.collect {
      case StatedBlock.Invalid(_, rejection, decodedHash) => (rejection, decodedHash.isDefined)
    }
    assert(
      refused == Vector((ExpectedRejection(Set("BlockException.INVALID_GASLIMIT")), true)),
      "the refusal and its decoded form are read, and the two further keys change neither: " + refused.toString
    )
  }

  it should "name each block that states a chain other than the default, and no block that states the default" in {
    // The older snapshot names every block's chain; `bcInvalidHeaderTest` names
    // `default` throughout, and its multi-chain tiers name others.
    val chained = (name: String) =>
      decoded(
        name,
        withBlock(published(PublishedParisCases.GasLimitCase), 0)(
          _.mapObject(_.add("chainname", Json.fromString(name)))
        )
      )
    val branch = chained("B").otherChains
    val main = chained("default").otherChains
    assert(
      branch == Vector(0 -> "B") && main.isEmpty,
      "a block on another chain is named with its position, and the default is no other chain: " + branch.toString +
        " " + main.toString
    )
  }

  it should "refuse a decoded form stating no header hash" in {
    val headless = withBlock(published(PublishedParisCases.GasLimitCase), 0)(
      _.mapObject(_.add("rlp_decoded", Json.obj("blocknumber" -> Json.fromString("1"))))
    )
    val read = BlockchainFixture.decodeCase("headless", headless)
    assert(
      read.left.exists(_.contains("rlp_decoded.blockHeader.hash")),
      "a decoded form with no header hash is a broken file, not a block that does not decode: " + read.toString
    )
  }

  it should "split a refusal stating alternatives into every name" in {
    val read =
      BlockchainFixture.decodeCase("alternatives", refusal(published(PublishedParisCases.SignatureCase), "A.ONE|B.TWO"))
    val stated = read.map(_.blocks.collect { case StatedBlock.Invalid(_, rejection, _) => rejection.stated })
    assert(
      stated == Right(Vector(Set("A.ONE", "B.TWO"))),
      "both alternatives are names the case states: " + stated.toString
    )
  }

  it should "refuse a refusal that names nothing" in
    assert(
      BlockchainFixture.decodeCase("empty", refusal(published(PublishedParisCases.SignatureCase), " | ")).isLeft,
      "a stated refusal with no name in it cannot be satisfied by anything, and reading it as one would diverge for " +
        "the reader's reason"
    )

  it should "refuse a refusal keyed another way, and name the key" in {
    val keyed = withBlock(published(PublishedParisCases.BlockHashCase), 1)(
      _.mapObject(_.add("expectExceptionALL", Json.fromString("InvalidStateRoot")))
    )
    val read = BlockchainFixture.decodeCase("keyed", keyed)
    assert(
      read.left.exists(_.contains("expectExceptionALL")),
      "a block refused under a key this reader does not resolve must not be read as a block the case accepts: " +
        read.toString
    )
  }

  it should "refuse a case stating its post-state as a root alone" in {
    val rooted = published(PublishedParisCases.BlockHashCase).mapObject(obj =>
      obj.remove("postState").add("postStateHash", Json.fromString("0x" + "00" * 32))
    )
    val read = BlockchainFixture.decodeCase("rooted", rooted)
    assert(
      read.left.exists(_.contains("postStateHash")),
      "a root is not an empty post-state, and reading it as one would diverge on every chain that holds anything: " +
        read.toString
    )
  }
