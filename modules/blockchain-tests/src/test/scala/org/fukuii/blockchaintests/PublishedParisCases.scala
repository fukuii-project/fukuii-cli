package org.fukuii.blockchaintests

import java.nio.charset.StandardCharsets

import scala.util.Using

import io.circe.Json

/** Three cases of the published `blockchain_tests` tier, read from this module's
  * test resources, so the reader and the runner are exercised without the corpus.
  *
  * ==Where they come from==
  *
  * `ethereum/execution-specs-fixtures` at the `tests-v20.0.1` release,
  * `blockchain_tests/for_paris`, one case each and keyed exactly as the release
  * keys them:
  *
  *   - [[BlockHashCase]], from `frontier/opcodes/blockhash/genesis_hash_available.json`
  *     -- two blocks, the second carrying one transaction that stores whether
  *     `BLOCKHASH` answers zero for the genesis and for the first block. The
  *     case expects neither to be zero, so it shows a hash is served for both
  *     and not which hash: the values themselves are decided by corpus cases
  *     whose code uses them;
  *   - [[GasLimitCase]], from
  *     `frontier/validation/header/block_gas_limit_below_minimum.json` -- one
  *     block the case refuses for its gas limit;
  *   - [[SignatureCase]], from `frontier/validation/transaction/bad_v_r_s.json`
  *     -- one block the case refuses for a transaction signed with a high `s`.
  *
  * **The values are the release's and the whitespace is not.** Each case, parsed,
  * equals the same case parsed from the release, and a copy with one value
  * altered does not -- which is what separates a comparison that reads the
  * values from one that cannot fail. The runner then verifies every hash and
  * root they state as it runs them, so a copying error that survived would
  * fail a named test rather than pass one.
  */
object PublishedParisCases:

  val BlockHashCase: String =
    "tests/frontier/opcodes/test_blockhash.py::test_genesis_hash_available" +
      "[fork_Paris-blockchain_test-one_block_with_tx]"

  val GasLimitCase: String =
    "tests/frontier/validation/test_header.py::test_block_gas_limit_below_minimum" +
      "[fork_Paris-minimum_minus_one-blockchain_test]"

  val SignatureCase: String =
    "tests/frontier/validation/test_transaction.py::test_bad_v_r_s" +
      "[fork_Paris-tx_type_0-blockchain_test_from_state_test-s=SECP256K1N//2+1]"

  private val Resource: String = "/published-paris-cases.json"

  /** The resource's text, or why it could not be had. */
  def text: Either[String, String] =
    Option(getClass.getResourceAsStream(Resource))
      .toRight("no resource " + Resource)
      .flatMap(stream =>
        Using(stream)(open => new String(open.readAllBytes(), StandardCharsets.UTF_8)).toEither.left.map(_.toString)
      )

  /** One case's JSON as the release states it. */
  def json(name: String): Either[String, Json] =
    text
      .flatMap(contents => io.circe.parser.parse(contents).left.map(_.getMessage))
      .flatMap(document => document.hcursor.downField(name).focus.toRight("no case " + name))

  /** One case, decoded by the reader under test. */
  def fixture(name: String): Either[String, BlockchainFixture] =
    json(name).flatMap(BlockchainFixture.decodeCase(name, _))
