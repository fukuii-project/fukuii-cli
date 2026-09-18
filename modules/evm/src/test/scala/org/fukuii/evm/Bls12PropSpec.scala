package org.fukuii.evm

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.fukuii.bytes.Bytes
import org.fukuii.evm.fixtures.FixtureCorpus

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** EIP-2537's seven operations against a published vector corpus.
  *
  * ==Why this corpus and not the state tests==
  *
  * The state tier exercises these through a transaction, so a case there settles
  * a state root and reports one bit about the whole block. These are the
  * operations' own inputs and outputs, byte for byte, which is what a backend
  * swap has to be checked against: a wrong answer inside a correct-looking state
  * root is exactly what a coarser tier cannot see.
  *
  * **The corpus is third-party.** `besu-eth/besu-native`'s own vectors, which
  * are neither this project's nor the backend's -- so an answer agreeing with
  * them is evidence rather than a round trip. They ship as CSV with the columns
  * `input,result,gas,notes`, and a blank `result` marks an input the operation
  * must refuse.
  *
  * ==The corpus is OPTIONAL and its absence is a skip, not a pass==
  *
  * It lives in the reference corpus rather than in this repository, so a clone
  * without that tree cannot run these. **A missing corpus cancels rather than
  * succeeding**, and the census property below fails if nothing was read at all
  * -- because `forAll` over an empty table succeeds, which would make every
  * property here green over a corpus that never loaded.
  *
  * ==What this does NOT check==
  *
  * Gas. The `gas` column is besu's own pricing of each case, and this build
  * prices from its own schedule; comparing them would test agreement between two
  * transcriptions rather than either against the proposal.
  * `org.fukuii.chainspec.proposals.eip.Eip2537Spec` prices against the
  * specification's figures instead.
  */
class Bls12PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** Where the vectors live, which is outside this repository.
    *
    * **Resolved through the corpus root rather than hardcoded**, for the reason
    * that root exists: a machine path written into tracked code resolves for
    * nobody else, and this tree is third-party material fetched and never
    * written -- exactly what that root already locates. The backend's own
    * organization is a `resolve` beside the others rather than a second pointer
    * to configure.
    */
  private val corpusRoot: Option[Path] =
    FixtureCorpus.root.map(
      _.resolve("besu-eth/besu-native/gnark/src/test/resources/org/hyperledger/besu/nativelib/gnark")
    )

  private def corpusPresent: Boolean = corpusRoot.exists(Files.isDirectory(_))

  /** One case: an input, and either the answer it must produce or nothing. */
  final private case class Published(operation: String, input: Bytes, expected: Option[String])

  private def read(file: String, operation: String): Seq[Published] =
    val path = corpusRoot.map(_.resolve(file))
    if !path.exists(Files.isReadable) then Seq.empty
    else
      Using.resource(Files.lines(path.get)): lines =>
        lines
          .iterator()
          .asScala
          .drop(1)
          .flatMap: line =>
            val columns = line.split(",", -1)
            if columns.length < 2 || columns(0).isEmpty then None
            else
              val answer = columns(1).trim
              Some(Published(operation, bytesOf(columns(0).trim), if answer.isEmpty then None else Some(answer)))
          .toVector

  /** The corpus writes some values bare and some with a `0x` prefix, so both are
    * accepted. An empty value is a real case -- several operations state an
    * empty input -- and yields empty bytes rather than failing to parse.
    */
  private def bytesOf(hex: String): Bytes =
    val body = if hex.startsWith("0x") || hex.startsWith("0X") then hex.drop(2) else hex
    Bytes.fromArray(body.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private def hexOf(bytes: Bytes): String = bytes.toIArray.map(b => f"${b & 0xff}%02x").mkString

  /** The corpus's own spelling of an expected answer, for comparison. */
  private def normalized(hex: String): String =
    val body = if hex.startsWith("0x") || hex.startsWith("0X") then hex.drop(2) else hex
    body.toLowerCase

  private def run(operation: String, input: Bytes): Either[Halt, Bytes] = operation match
    case "g1add"   => Bls12.g1Add(input)
    case "g1msm"   => Bls12.g1Msm(input)
    case "g2add"   => Bls12.g2Add(input)
    case "g2msm"   => Bls12.g2Msm(input)
    case "pairing" => Bls12.pairing(input)
    case "fp2g1"   => Bls12.mapFpToG1(input)
    case "fp22g2"  => Bls12.mapFp2ToG2(input)
    case other     => throw new AssertionError("no such operation: " + other)

  private val cases: Seq[Published] =
    read("g1_add.csv", "g1add") ++
      read("g1_mul.csv", "g1msm") ++
      read("g1_multiexp.csv", "g1msm") ++
      read("g2_add.csv", "g2add") ++
      read("g2_mul.csv", "g2msm") ++
      read("g2_multiexp.csv", "g2msm") ++
      read("pairing.csv", "pairing") ++
      read("invalid_subgroup_for_pairing.csv", "pairing") ++
      read("fp_to_g1.csv", "fp2g1") ++
      read("fp2_to_g2.csv", "fp22g2")

  private val answering: Seq[Published] = cases.filter(_.expected.isDefined)

  private val refusing: Seq[Published] = cases.filter(_.expected.isEmpty)

  property("the corpus was found, or every property here checks nothing"):
    // THE CENSUS, and it is the property the rest depend on. `forAll` over an
    // empty table succeeds, so a corpus that failed to load would leave every
    // case below green while checking nothing at all.
    val _ = assume(corpusPresent, "the reference corpus is not on this machine")
    assert(
      cases.length > 1000,
      s"expected the published corpus, read ${cases.length} cases"
    )

  property("the backend loaded, or a refusal means nothing"):
    val _ = assume(corpusPresent, "the reference corpus is not on this machine")
    // Without this, a backend that failed to load answers every case with the
    // same refusal -- which satisfies every negative case below and fails only
    // the positives, reading as a correctness problem rather than a missing
    // library.
    assert(Bls12.available, "the native backend did not load, so no answer below is about the operations")

  property("every stated answer is reproduced byte for byte"):
    val _ = assume(corpusPresent && Bls12.available, "corpus or backend absent")
    val _ = assert(answering.nonEmpty, "no answering cases were read")
    forAll(Table("case", answering*)): vector =>
      val produced = run(vector.operation, vector.input).map(hexOf)
      assert(
        produced == Right(normalized(vector.expected.get)),
        vector.operation + ": expected " + normalized(vector.expected.get) + ", got " + produced.fold(
          _ => "a refusal",
          identity
        )
      )

  property("every input the corpus refuses is refused"):
    val _ = assume(corpusPresent && Bls12.available, "corpus or backend absent")
    val _ = assert(refusing.nonEmpty, "no refusing cases were read, so this property discriminates nothing")
    forAll(Table("case", refusing*)): vector =>
      assert(
        run(vector.operation, vector.input).isLeft,
        s"${vector.operation}: an input the corpus states invalid was answered"
      )

  property("the corpus states both kinds, or one of the two properties above is vacuous"):
    // The discriminator. A corpus of answers alone would let a build that never
    // refuses pass; a corpus of refusals alone would let one that always refuses
    // pass. Both counts are asserted so neither property can be satisfied by a
    // build that only ever does one thing.
    val _ = assume(corpusPresent, "the reference corpus is not on this machine")
    assert(
      answering.nonEmpty && refusing.nonEmpty,
      s"answering=${answering.length} refusing=${refusing.length}"
    )
