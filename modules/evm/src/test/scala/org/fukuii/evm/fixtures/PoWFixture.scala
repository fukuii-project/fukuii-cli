package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.{Bytes, Hash}

/** One published proof-of-work case: a sealed header, and every intermediate
  * the algorithm passes through on its way to accepting it.
  *
  * ==Its own tier and its own reader, for the reason every tier has one==
  *
  * `PoWTests` is a flat map from case name to case, with no fork level and no
  * state anywhere in it, so neither [[StateFixture]] nor [[DifficultyFixture]]
  * reads it. It is also the only tier read here whose hex carries **no `0x`
  * prefix**, which [[FixtureValues.bytesOf]] already tolerates and which is
  * worth knowing before a value is compared against one that does.
  *
  * ==The intermediates are the reason this tier is worth more than its case
  * count==
  *
  * A case states the seed, the cache size, the dataset size and the seal hash
  * beside the answer, so a divergence lands on the step that caused it rather
  * than on the digest at the end. Two cases checked at eight points is a better
  * instrument than two cases checked at one, and the published tier is only two
  * cases.
  *
  * @param header
  *   the whole sealed header as RLP, including the two elements the seal hash
  *   removes. Decoding it and re-deriving [[headerHash]] is what checks the
  *   removal, which no other field in the case can.
  * @param cacheHash
  *   a digest over the generated cache. **No client in this project's corpus
  *   asserts it** -- `NethermindEth/nethermind` @ `c35ce1b1a` binds it in
  *   `EthashTestJson.CacheHash` and never reads it -- so what it digests is
  *   established here by agreeing with one, not by reading a consumer.
  */
final case class PoWFixture(
    name: String,
    header: Bytes,
    nonce: Bytes,
    mixHash: Hash,
    seed: Hash,
    result: Hash,
    headerHash: Hash,
    cacheHash: Hash,
    cacheSize: Long,
    datasetSize: Long
)

object PoWFixture:

  /** The key a file may wrap its metadata under, which carries no case. */
  private val MetadataKey: String = "_info"

  /** Every case in one file.
    *
    * A file states its cases at the top level with no fork key above them,
    * which is the whole structural difference from [[DifficultyFixture]]: there
    * is no fork to resolve, because a seal's algorithm is the engine's and no
    * fork selects one.
    */
  def decodeFile(path: String, contents: String): Either[String, Vector[PoWFixture]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": not JSON (" + error.getMessage + ")")
      .flatMap(decodeCases(path, _))

  private def decodeCases(path: String, json: Json): Either[String, Vector[PoWFixture]] =
    json.asObject match
      case None      => Left(path + ": the file is not a JSON object")
      case Some(obj) =>
        obj.toVector
          .filterNot((name, _) => name == MetadataKey)
          .foldLeft[Either[String, Vector[PoWFixture]]](Right(Vector.empty)) { case (acc, (name, body)) =>
            for
              seen <- acc
              one <- decodeCase(name, body).left.map(error => path + " / " + name + ": " + error)
            yield seen :+ one
          }

  private[fixtures] def decodeCase(name: String, json: Json): Either[String, PoWFixture] =
    for
      header <- FixtureValues.bytesAt(json, "header")
      nonce <- FixtureValues.bytesAt(json, "nonce")
      mixHash <- hashAt(json, "mixHash")
      seed <- hashAt(json, "seed")
      result <- hashAt(json, "result")
      headerHash <- hashAt(json, "header_hash")
      cacheHash <- hashAt(json, "cache_hash")
      cacheSize <- sizeAt(json, "cache_size")
      datasetSize <- sizeAt(json, "full_size")
    yield PoWFixture(name, header, nonce, mixHash, seed, result, headerHash, cacheHash, cacheSize, datasetSize)

  private def hashAt(json: Json, field: String): Either[String, Hash] =
    FixtureValues.stringAt(json, field).flatMap(FixtureValues.hashOf)

  /** A size, which this tier writes as a JSON NUMBER where every other field is
    * a hex string.
    *
    * Read through circe's own number decoding rather than through
    * [[FixtureValues.quantity]], which takes a string. A reader reaching for the
    * hex path here gets a decode failure per case, which counts as a skip and
    * therefore as neither agreement nor divergence.
    */
  private def sizeAt(json: Json, field: String): Either[String, Long] =
    json.hcursor.downField(field).as[Long].left.map(_ => "not a whole number at " + field)
