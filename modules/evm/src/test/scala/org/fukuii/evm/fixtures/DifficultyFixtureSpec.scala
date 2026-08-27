package org.fukuii.evm.fixtures

import io.circe.Json

import org.scalatest.flatspec.AnyFlatSpec

/** What the difficulty reader does with the spellings the published tiers use.
  *
  * ==The corpus runs exercise three of these four and never the refusal==
  *
  * `DifficultyCertificationSpec` reads 18,598 cases that state `parentUncles`
  * as a count and `BasicTestsDifficultyCertificationSpec` 120 that state it not
  * at all, so both readings are covered by a tier. The ommers-hash spelling is
  * covered by neither, because the one file wired from `BasicTests` does not use
  * it -- and a refusal nothing ever reaches is indistinguishable from a refusal
  * that does not work.
  */
class DifficultyFixtureSpec extends AnyFlatSpec:

  /** The ommers hash a parent with no ommers carries, which is the value that
    * makes the refusal necessary rather than tidy.
    *
    * Read as a quantity it is enormous and therefore nonzero, so a count reader
    * would answer that this parent HAD ommers -- the inverted reading, on the
    * commonest value in the four files that spell the field this way.
    */
  private val EmptyOmmersHash: String = "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"

  private def field(name: String, value: String): String = "\"" + name + "\": \"" + value + "\""

  private def caseWith(parentUncles: Option[String]): Json =
    val stated =
      Vector(
        field("currentBlockNumber", "0x0dbba0"),
        field("currentTimestamp", "0x0b"),
        field("parentDifficulty", "0x03e8"),
        field("parentTimestamp", "0x0a"),
        field("currentDifficulty", "0x020000")
      ) ++ parentUncles.map(field("parentUncles", _))
    io.circe.parser
      .parse(stated.mkString("{", ", ", "}"))
      .getOrElse(throw new IllegalStateException("the fixture this spec builds is not valid JSON"))

  private def decoded(parentUncles: Option[String]): Either[String, DifficultyFixture] =
    DifficultyFixture.decodeCase("case", "Homestead", caseWith(parentUncles))

  "a case stating parentUncles as a count" should "read a nonzero count as a parent that had them" in
    assert(decoded(Some("0x01")).map(_.parentHasOmmers).contains(true))

  it should "read a zero count as a parent that had none" in
    assert(decoded(Some("0x00")).map(_.parentHasOmmers).contains(false))

  "a case stating no parentUncles at all" should "read as a parent that had none" in
    assert(
      decoded(None).map(_.parentHasOmmers).contains(false),
      "the tier spelling it this way states graduated-rule cases, whose rule reads no ommer term"
    )

  "a case stating parentUncles as an ommers hash" should "be refused rather than read as a count" in
    assert(
      decoded(Some(EmptyOmmersHash)).left.exists(_.contains("ommers hash")),
      "read as a quantity the empty-list hash is nonzero, so accepting it would answer that a parent with no " +
        "ommers had them, on every case in the four files that spell the field this way"
    )

  "a file stating its cases under no fork key" should "yield every case it holds" in
    assert(
      DifficultyFixture
        .decodeFlatFile(
          "flat.json",
          "Homestead",
          "{\"One\": " + caseWith(None).noSpaces + ", \"Two\": " + caseWith(Some("0x01")).noSpaces + "}"
        )
        .map(_.map(_.name)) == Right(Vector("flat.json Homestead One", "flat.json Homestead Two")),
      "the shape the nested reader would answer with no cases at all, rather than with an error"
    )
