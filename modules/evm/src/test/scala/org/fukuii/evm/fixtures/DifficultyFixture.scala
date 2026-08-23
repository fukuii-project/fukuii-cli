package org.fukuii.evm.fixtures

import io.circe.Json

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
  *   the fixture's `parentUncles`, which is a flag and not a hash. Every case in
  *   the corpus writes `0x00` or `0x01`, so it is read as a quantity and tested
  *   against zero rather than parsed as a list or compared to a digest.
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
      ommers <- FixtureValues.quantityAt(json, "parentUncles").left.map(name + ": " + _)
      expected <- FixtureValues.quantityAt(json, "currentDifficulty").left.map(name + ": " + _)
    yield DifficultyFixture(
      name = name,
      fork = fork,
      blockNumber = blockNumber,
      timestamp = timestamp,
      parentDifficulty = parentDifficulty,
      parentTimestamp = parentTimestamp,
      parentHasOmmers = ommers != 0,
      expected = expected
    )
