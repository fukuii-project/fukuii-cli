package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.Bytes
import org.fukuii.evm.fixtures.ExpectedRejection

/** The runner's two comparisons whose variants differ only by the value they
  * state, as tables over [[BlockchainRunnerCases]]'s one-change variants of a
  * published case.
  *
  * ==Why these two, as tables==
  *
  * Whether a refused block that does not decode satisfies the name the case
  * refuses it under, and whether a stated blob schedule is the one its forks'
  * rules resolve, are each one comparison, and each row is an input and an
  * expected outcome and nothing else. Each table holds its comparison's control
  * as rows: a block that does not decode under a name whose rule is not that,
  * and a blob schedule some fork's rules do not resolve. A row that diverges
  * asserts the reason it names, so a row diverging for some unrelated reason
  * still fails.
  *
  * `BlockchainRunnerSpec` holds the examples: the published cases run whole, and
  * every variant that differs in what it changes or reads rather than only in
  * what it states.
  */
class BlockchainRunnerPropSpec extends AnyPropSpec with TableDrivenPropertyChecks with BlockchainRunnerCases:

  property("a refused block that does not decode agrees exactly where the name the case states holds its failure") {
    val undecodable = Table(
      ("refused block", "encoding", "names", "divergence"),
      (
        "a header of no fork's shape, under the structures name",
        headerOfNoForkShape,
        StructuresName,
        Option.empty[String]
      ),
      ("a header of no fork's shape, under the format name", headerOfNoForkShape, FormatName, Option.empty[String]),
      ("a body of no block's shape, under the format name", bodyOfNoShape, FormatName, Some("block 0 does not decode")),
      ("a body of no block's shape, under the structures name", bodyOfNoShape, StructuresName, Option.empty[String]),
      ("bytes that are not RLP, under the structures name", NotRlp, StructuresName, Some("block 0 does not decode")),
      (
        "bytes that decode to no block, under the gas-limit name",
        Undecodable,
        GasLimitName,
        Some("block 0 does not decode")
      )
    )
    forAll(undecodable) { (refused: String, encoding: Bytes, names: ExpectedRejection, divergence: Option[String]) =>
      val result = BlockchainRunner.run(withRefusal(encoding, names, None))
      val outcome = divergence.fold(agreed(result) && result.refusalsAgreed == 1) { fragment =>
        divergedFor(result, fragment) && result.refusalsAgreed == 0
      }
      assert(outcome, refused + ": " + result.toString)
    }
  }

  property("a blob schedule agrees exactly where each entry states its own fork's rules") {
    val schedules = Table(
      ("the schedule states", "case", "divergence"),
      ("exactly the rules of the fork it names", scheduling("Cancun" -> CancunBlobs), Option.empty[String]),
      (
        "another value",
        scheduling("Cancun" -> CancunBlobs.copy(max = Some(BigInt(7)))),
        Some("blob schedule for Cancun states max 7, where its rules resolve 6")
      ),
      (
        "an entry omitting a member",
        scheduling("Cancun" -> CancunBlobs.copy(baseFeeUpdateFraction = None)),
        Some("states no baseFeeUpdateFraction")
      ),
      (
        "a fork this runner resolves no rules for",
        scheduling("Cancun" -> CancunBlobs, "NotAPublishedFork" -> CancunBlobs),
        Some("for NotAPublishedFork names a fork this runner resolves no rules for")
      ),
      (
        "a fork whose rules account for no blob gas",
        scheduling("Shanghai" -> CancunBlobs),
        Some("for Shanghai names a fork whose rules this build resolves with no blob")
      ),
      (
        "an entry stating a key this runner does not compare",
        scheduling("Cancun" -> CancunBlobs.copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY"))),
        Some("for Cancun states NOT_A_PUBLISHED_KEY")
      )
    )
    forAll(schedules) { (stated: String, fixture: BlockchainFixture, divergence: Option[String]) =>
      val result = BlockchainRunner.run(fixture)
      assert(divergence.fold(agreed(result))(divergedFor(result, _)), stated + ": " + result.toString)
    }
  }
