package org.fukuii.evm.fixtures

import io.circe.Json

import org.fukuii.bytes.Hash

/** One published difficulty case: a parent, a block, and the difficulty the
  * corpus says that block must carry.
  *
  * ==Its own tier and its own reader, because no other one fits==
  *
  * `.claude/reference-corpus.md` maps `DifficultyTests` as a tier of
  * `ethereum/tests` that nothing in this repository read until a difficulty rule
  * was built, and each published tier is a different format. A state fixture is
  * an `env`, a `pre`, a transaction and a `post` keyed by fork; this is six
  * scalars keyed by fork and then by case name, with no state anywhere in it.
  *
  * ==The fork key names the algorithm, not a network's history==
  *
  * A file states its cases under one fork name and the corpus partitions by
  * directory as well, so the two agree here and neither is derivable from the
  * other. The name is carried through unmapped for the reason
  * [[ExpectedRejection]] carries a corpus's own wording unmapped: whoever runs
  * the case decides which rules that name resolves to, and a name resolving to
  * none is a divergence rather than a case that quietly disappears.
  *
  * @param parentHasOmmers
  *   the fixture's `parentUncles`, read as a count and tested against zero.
  *
  *   **Two tiers spell that field differently and only one of them is read this
  *   way.** Every case under `ethereum/tests/DifficultyTests` @ `v17.2` writes
  *   `0x00` or `0x01` -- 9,301 and 9,297 of the 18,598 -- so a count is the
  *   whole of what that tier states. Four of the five difficulty files under
  *   `BasicTests` in the same tree write an ommers hash instead, and
  *   [[DifficultyFixture.decodeCase]] refuses that spelling rather than reading
  *   it as a very large count, which is what a quantity reader would silently
  *   do with it.
  */
final case class DifficultyFixture(
    name: String,
    fork: String,
    blockNumber: BigInt,
    timestamp: BigInt,
    parentDifficulty: BigInt,
    parentTimestamp: BigInt,
    parentHasOmmers: Boolean,
    expected: BigInt
)

object DifficultyFixture:

  /** The key a file wraps its metadata under, which is not a fork and carries no
    * cases.
    */
  private val MetadataKey: String = "_info"

  /** Every case in one file, across every fork the file states cases under.
    *
    * ==The file is read for all its forks rather than for one named by the
    * caller==
    *
    * This is the opposite of [[StateFixture.decodeFile]] and the difference is
    * the corpus's, not a preference. A state fixture states expectations for
    * many forks over one input, so asking it about one fork is the whole
    * question. A difficulty file states cases for exactly one fork and names
    * that fork in its own directory, so a caller naming a fork could only either
    * agree with the file or silently read nothing.
    *
    * **A case that names no fork the runner knows is therefore visible as a
    * case**, counted and reported, rather than as an empty read.
    */
  def decodeFile(path: String, contents: String): Either[String, Vector[DifficultyFixture]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap { json =>
        json.asObject.toRight(path + ": expected an object").flatMap { outer =>
          outer.toVector.foldLeft(Right(Vector.empty): Either[String, Vector[DifficultyFixture]]) {
            case (Left(error), _)            => Left(error)
            case (Right(sofar), (_, byFork)) =>
              decodeForks(path, byFork).map(sofar ++ _)
          }
        }
      }

  private def decodeForks(path: String, json: Json): Either[String, Vector[DifficultyFixture]] =
    json.asObject.toRight(path + ": expected an object of forks").flatMap { forks =>
      forks.toVector
        .filterNot { case (key, _) => key == MetadataKey }
        .foldLeft(Right(Vector.empty): Either[String, Vector[DifficultyFixture]]) {
          case (Left(error), _)              => Left(error)
          case (Right(sofar), (fork, cases)) =>
            decodeCases(path, fork, cases).map(sofar ++ _)
        }
    }

  /** Every case in one file that states its cases directly, under no fork key.
    *
    * ==A second shape, because the tier states one and not because a reader
    * wanted one==
    *
    * `ethereum/tests` @ `v17.2` nests a `DifficultyTests` file as file name,
    * then fork, then case, with an `_info` block beside the fork. Its
    * `BasicTests` difficulty files are case name to case at the top level, with
    * no fork key and no metadata block anywhere. [[decodeFile]] reads the first
    * and this reads the second; pointing either at the other's shape yields no
    * cases rather than an error, which is why the two are separate entry points
    * instead of one that guesses.
    *
    * @param fork
    *   which rules the caller believes the file's cases are stated under. The
    *   file names none and neither does its directory, so the belief is the
    *   caller's and is carried into every case for the same reason
    *   [[decodeFile]] carries the fork key it reads: a name resolving to no
    *   rules must surface as a divergence rather than as a case that quietly
    *   disappears.
    */
  def decodeFlatFile(path: String, fork: String, contents: String): Either[String, Vector[DifficultyFixture]] =
    io.circe.parser
      .parse(contents)
      .left
      .map(error => path + ": " + error.getMessage)
      .flatMap(decodeCases(path, fork, _))

  private def decodeCases(path: String, fork: String, json: Json): Either[String, Vector[DifficultyFixture]] =
    json.asObject.toRight(path + " " + fork + ": expected an object of cases").flatMap { cases =>
      cases.toVector.foldLeft(Right(Vector.empty): Either[String, Vector[DifficultyFixture]]) {
        case (Left(error), _)             => Left(error)
        case (Right(sofar), (name, body)) =>
          decodeCase(path + " " + fork + " " + name, fork, body).map(sofar :+ _)
      }
    }

  private[fixtures] def decodeCase(name: String, fork: String, json: Json): Either[String, DifficultyFixture] =
    for
      blockNumber <- FixtureValues.quantityAt(json, "currentBlockNumber").left.map(name + ": " + _)
      timestamp <- FixtureValues.quantityAt(json, "currentTimestamp").left.map(name + ": " + _)
      parentDifficulty <- FixtureValues.quantityAt(json, "parentDifficulty").left.map(name + ": " + _)
      parentTimestamp <- FixtureValues.quantityAt(json, "parentTimestamp").left.map(name + ": " + _)
      ommers <- ommersAt(json).left.map(name + ": " + _)
      expected <- FixtureValues.quantityAt(json, "currentDifficulty").left.map(name + ": " + _)
    yield DifficultyFixture(
      name = name,
      fork = fork,
      blockNumber = blockNumber,
      timestamp = timestamp,
      parentDifficulty = parentDifficulty,
      parentTimestamp = parentTimestamp,
      parentHasOmmers = ommers,
      expected = expected
    )

  /** Whether the case says the parent carried ommers, over the spellings the
    * corpus actually uses for it.
    *
    * ==An absent field is no ommers, and is only sound where the rule cannot
    * read it==
    *
    * The 120 cases of `BasicTests/difficultyCustomHomestead.json` in
    * `ethereum/tests` @ `v17.2` state no `parentUncles` at all. That file's
    * cases are graduated-rule cases, and the graduated rule takes no ommer
    * term -- `ethereum/execution-specs` @ `ccaaaba58` gives
    * `calculate_block_difficulty` a `parent_has_ommers` parameter only from
    * `forks/byzantium/fork.py` onward -- so the value read here reaches no
    * arithmetic for such a file. A tier whose rules DO read it must state the
    * field, and the harness certifying one is what asserts that.
    *
    * ==A hash is refused rather than read as a count==
    *
    * Four of the five `BasicTests` difficulty files write an ommers hash where
    * this one writes nothing and `DifficultyTests` writes `0x00` or `0x01`.
    * `NethermindEth/nethermind` @ `c35ce1b1a` and
    * `openethereum/openethereum` @ `v3.0.1` both answer that spelling by
    * comparing against the hash of the empty list --
    * `parent.UnclesHash != Keccak.OfAnEmptySequenceRlp` and
    * `parent.uncles_hash() != &KECCAK_EMPTY_LIST_RLP` -- which needs a constant
    * no part of this build carries.
    *
    * **Refused rather than defaulted, because the failure would be silent and
    * inverted.** A quantity reader accepts a 32-byte hash, and the hash
    * standing for NO ommers is nonzero, so every such case would read as a
    * parent that had them. Refusing turns wiring one of those files into a
    * decode failure naming the field, which is the loud half of the same
    * outcome.
    */
  private def ommersAt(json: Json): Either[String, Boolean] =
    json.hcursor.downField("parentUncles").focus match
      case None       => Right(false)
      case Some(held) =>
        for
          text <- held.asString.toRight("parentUncles is not a string")
          raw <- FixtureValues.bytesOf(text)
          _ <- Either.cond(
            raw.length != Hash.Width,
            (),
            "parentUncles is a " + Hash.Width.toString + "-byte ommers hash, which this reader has no empty-list " +
              "hash to compare against: " + text
          )
          count <- FixtureValues.quantity(text)
        yield count != 0
