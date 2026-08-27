package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.fukuii.chainspec.proposals.eip.Eip2

/** The harness read against fixtures held here rather than on disk, so that it
  * is exercised in a clone that has no corpus at all.
  *
  * ==What these tests are for==
  *
  * A harness whose only tests need an external corpus reports nothing when the
  * corpus is absent, and reports nothing in exactly the same way it reports
  * agreement. Each pairing below runs one published fixture, then runs it again
  * with one expected value corrupted, and requires the second to be reported as
  * a divergence. A harness that had stopped comparing would pass the first and
  * fail the second.
  *
  * The two fixtures are verbatim from the corpora, minus the fields this fork
  * does not read, so their expected values are external rather than computed
  * here.
  */
class FixtureCalibrationSpec extends AnyFlatSpec:

  private val vmFixture: String =
    """{
      |  "add0": {
      |    "env": {
      |      "currentCoinbase": "0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba",
      |      "currentDifficulty": "0x020000",
      |      "currentGasLimit": "0x7fffffffffffffff",
      |      "currentNumber": "0x01",
      |      "currentTimestamp": "0x03e8"
      |    },
      |    "exec": {
      |      "address": "0x0f572e5295c57f15886f9b263e2f6d2d6c7b5ec6",
      |      "caller": "0xcd1722f2947def4cf144679da39c4c32bdc35681",
      |      "code": "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0160005500",
      |      "data": "0x",
      |      "gas": "0x0186a0",
      |      "gasPrice": "0x0c",
      |      "origin": "0xcd1722f2947def4cf144679da39c4c32bdc35681",
      |      "value": "0x0b"
      |    },
      |    "gas": "0x013874",
      |    "logs": "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
      |    "out": "0x",
      |    "post": {
      |      "0x0f572e5295c57f15886f9b263e2f6d2d6c7b5ec6": {
      |        "balance": "0x0de0b6b3a7640000",
      |        "code": "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0160005500",
      |        "nonce": "0x00",
      |        "storage": {
      |          "0x00": "0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe"
      |        }
      |      }
      |    },
      |    "pre": {
      |      "0x0f572e5295c57f15886f9b263e2f6d2d6c7b5ec6": {
      |        "balance": "0x0de0b6b3a7640000",
      |        "code": "0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0160005500",
      |        "nonce": "0x00",
      |        "storage": {}
      |      }
      |    }
      |  }
      |}""".stripMargin

  /** A published fixture whose transaction is REJECTED at admission, embedded
    * because the corpus is not in this repository and this suite must run in a
    * clone that has none of it.
    *
    * Every other case here uses a fixture that is admitted, so admission's
    * refusal branches -- and the comparison branch that reads
    * `expectException` -- were exercised by nothing that runs without the
    * corpus. Dropping a refusal branch would have failed no test a clone can
    * run.
    *
    * Taken verbatim from
    * `state_tests/for_frontier/frontier/validation/transaction/sender_balance_insufficient_state_test.json`
    * at the `tests@v20.0.1` release, compacted only in whitespace.
    */
  private val rejectionFixture: String =
    "{\"tests/frontier/validation/test_transaction.py::test_sender_balance_insufficient_state_test[fork_Frontier-state_test]\":{\"env\":{\"currentCoinbase\":\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\",\"currentGasLimit\":\"0x07270e00\",\"currentNumber\":\"0x01\",\"currentTimestamp\":\"0x03e8\",\"currentDifficulty\":\"0x020000\"},\"pre\":{\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"transaction\":{\"nonce\":\"0x00\",\"gasPrice\":\"0x0a\",\"gasLimit\":[\"0x0186a0\"],\"to\":\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\",\"value\":[\"0x00\"],\"data\":[\"0x\"],\"sender\":\"0xc0f6dc9e5836f54caadbf59cc69346c508e1992b\",\"secretKey\":\"0x4a2ffc8867fd8d1773481cf13f36e44f033133c579520d2745e46c3bbbf21e6a\"},\"post\":{\"Frontier\":[{\"hash\":\"0x7d2b9aab793f618eeb1773120fd525386b788a3947bd47ec6a7cfea9e51d1b6d\",\"logs\":\"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\",\"txbytes\":\"0xf860800a830186a09443e6f595ac8e8c6e584c7a2d9949a6bfae8c428780801ba02dda4459c3a9ad10958d14dfb46995f67a72daf0dae7a59ec01282848b13c969a059d5c99684510838ebaea7f337bc9599364414b12acbde5223ed0d265896fa48\",\"indexes\":{\"data\":0,\"gas\":0,\"value\":0},\"state\":{\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"expectException\":\"TransactionException.INSUFFICIENT_ACCOUNT_FUNDS\"}]},\"config\":{\"chainid\":\"0x01\"},\"_info\":{\"hash\":\"0xcbdc4bcefca612f316909b9b2134198a359a51ac8a0c178b2eafd9d9d16d8845\",\"fixture-format\":\"state_test\",\"comment\":\"`execution-specs` generated test\",\"filling-transition-tool\":\"2.19.0\",\"description\":\"A legacy transaction from a sender that cannot afford `gas * gasPrice`\\nmust be rejected, exercised through the state-test code path.\",\"url\":\"https://github.com/ethereum/execution-specs/blob/87aba1a38a476b31f819a2390eb481527e6dc683/tests/frontier/validation/test_transaction.py#L208\",\"metadata\":{\"opcode_count\":{}}}}}"

  /** A published fixture that carries the receipt its transaction leaves,
    * embedded for the reason the one above is: this suite must run in a clone
    * that has no corpus.
    *
    * The two fixtures below it are the SAME published test filled for two
    * later forks, so the three differ in as little as the corpus allows. Taken
    * from `state_tests/for_frontier/frontier/touch/touch/`
    * `zero_gas_price_and_touching.json` at the `tests@v20.0.1` release,
    * compacted in whitespace and with the `_info` block dropped.
    */
  private val receiptFixture: String =
    "{\"tests/frontier/touch/test_touch.py::test_zero_gas_price_and_touching[fork_Frontier-state_test]\":{\"env\":{\"currentCoinbase\":\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\",\"currentGasLimit\":\"0x07270e00\",\"currentNumber\":\"0x01\",\"currentTimestamp\":\"0x03e8\",\"currentDifficulty\":\"0x020000\"},\"pre\":{\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x00\",\"balance\":\"0x033b2e3c9fd0803ce8000000\",\"code\":\"0x\",\"storage\":{}},\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"transaction\":{\"nonce\":\"0x00\",\"gasPrice\":\"0x00\",\"gasLimit\":[\"0x07270e00\"],\"to\":\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\",\"value\":[\"0x00\"],\"data\":[\"0x\"],\"sender\":\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\",\"secretKey\":\"0x1ad604b3d94e06ec50b27732ec677f3d857c4d588f082f0016317697dd3a2d92\"},\"post\":{\"Frontier\":[{\"hash\":\"0x79ad7c23da2e7644a83c50103595e81fa897f171b39a79bbbdb147859f35cc43\",\"logs\":\"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\",\"receipt\":{\"transactionHash\":\"0x3358058cf4aa59cb8d80ea164bc26affac51cebe9d56da1d9fb73875873d16aa\",\"type\":\"0x00\",\"cumulativeGasUsed\":\"0xa02e\",\"bloom\":\"0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\",\"logs\":[],\"postState\":\"0x79ad7c23da2e7644a83c50103595e81fa897f171b39a79bbbdb147859f35cc43\",\"rlp\":\"0xf90128a079ad7c23da2e7644a83c50103595e81fa897f171b39a79bbbdb147859f35cc4382a02eb9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c0\"},\"txbytes\":\"0xf86180808407270e009443e6f595ac8e8c6e584c7a2d9949a6bfae8c428780801ba0e930668f6323b055c87344cd649f0508b177c095c1b6034bb27f61d9b221b664a03086821cd994e6e51531de905dbaa89caef00b7fe87c1967f668e0dac36fc78e\",\"indexes\":{\"data\":0,\"gas\":0,\"value\":0},\"state\":{\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x01\",\"balance\":\"0x033b2e3c9fd0803ce8000000\",\"code\":\"0x\",\"storage\":{}},\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{\"0x00\":\"0x01\"}},\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\":{\"nonce\":\"0x00\",\"balance\":\"0x00\",\"code\":\"0x\",\"storage\":{}}}}]},\"config\":{\"chainid\":\"0x01\"}}}"

  /** The same published test filled for the fork that replaces a receipt's
    * first field, from `state_tests/for_byzantium/frontier/touch/touch/`
    * `zero_gas_price_and_touching.json` at the same release.
    *
    * Its `env`, `pre` and `transaction` are byte-identical to the fixture
    * above and its receipt states `status` where that one states `postState`,
    * which is what makes running it a question about one rule rather than
    * about a different test.
    */
  private val statusReceiptFixture: String =
    "{\"tests/frontier/touch/test_touch.py::test_zero_gas_price_and_touching[fork_Byzantium-state_test]\":{\"env\":{\"currentCoinbase\":\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\",\"currentGasLimit\":\"0x07270e00\",\"currentNumber\":\"0x01\",\"currentTimestamp\":\"0x03e8\",\"currentDifficulty\":\"0x020000\"},\"pre\":{\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x00\",\"balance\":\"0x033b2e3c9fd0803ce8000000\",\"code\":\"0x\",\"storage\":{}},\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"transaction\":{\"nonce\":\"0x00\",\"gasPrice\":\"0x00\",\"gasLimit\":[\"0x07270e00\"],\"to\":\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\",\"value\":[\"0x00\"],\"data\":[\"0x\"],\"sender\":\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\",\"secretKey\":\"0x1ad604b3d94e06ec50b27732ec677f3d857c4d588f082f0016317697dd3a2d92\"},\"post\":{\"Byzantium\":[{\"hash\":\"0xe68fdc8013ef9ed8f546616b2082623997955c016528d06e9c9a24e5a55573b3\",\"logs\":\"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\",\"receipt\":{\"transactionHash\":\"0x3358058cf4aa59cb8d80ea164bc26affac51cebe9d56da1d9fb73875873d16aa\",\"type\":\"0x00\",\"cumulativeGasUsed\":\"0xa02e\",\"bloom\":\"0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\",\"logs\":[],\"status\":true,\"rlp\":\"0xf901080182a02eb9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c0\"},\"txbytes\":\"0xf86180808407270e009443e6f595ac8e8c6e584c7a2d9949a6bfae8c428780801ba0e930668f6323b055c87344cd649f0508b177c095c1b6034bb27f61d9b221b664a03086821cd994e6e51531de905dbaa89caef00b7fe87c1967f668e0dac36fc78e\",\"indexes\":{\"data\":0,\"gas\":0,\"value\":0},\"state\":{\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x01\",\"balance\":\"0x033b2e3c9fd0803ce8000000\",\"code\":\"0x\",\"storage\":{}},\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{\"0x00\":\"0x01\"}}}}]},\"config\":{\"chainid\":\"0x01\"}}}"

  /** A published case at the same fork whose transaction FAILED, so that the
    * other status is read from published octets too rather than only from a
    * value this project chose.
    *
    * One case lifted from `state_tests/for_byzantium/frontier/opcodes/swap/`
    * `stack_underflow.json` at the same release -- the file holds one per
    * `SWAP` operation and this is `SWAP1`, whose code swaps against an empty
    * stack. Its receipt states `"status": false` and its published octets carry
    * that as the empty string the scalar rule requires.
    */
  private val failedReceiptFixture: String =
    "{\"tests/frontier/opcodes/test_swap.py::test_stack_underflow[fork_Byzantium-state_test-SWAP1]\":{\"env\":{\"currentCoinbase\":\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\",\"currentGasLimit\":\"0x07270e00\",\"currentNumber\":\"0x01\",\"currentTimestamp\":\"0x03e8\",\"currentDifficulty\":\"0x020000\"},\"pre\":{\"0x93b696170807b34317f5e2ec7151fd738b6e4125\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x90600055\",\"storage\":{}},\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x00\",\"balance\":\"0x033b2e3c9fd0803ce8000000\",\"code\":\"0x\",\"storage\":{}}},\"transaction\":{\"chainId\":\"0x01\",\"nonce\":\"0x00\",\"gasPrice\":\"0x0a\",\"gasLimit\":[\"0x07270e00\"],\"to\":\"0x93b696170807b34317f5e2ec7151fd738b6e4125\",\"value\":[\"0x00\"],\"data\":[\"0x\"],\"sender\":\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\",\"secretKey\":\"0x1ad604b3d94e06ec50b27732ec677f3d857c4d588f082f0016317697dd3a2d92\"},\"post\":{\"Byzantium\":[{\"hash\":\"0x4c3457c70ae44ece0c4b05b7b70b39b0d60b683e35b212ca2db8359687206da0\",\"logs\":\"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\",\"receipt\":{\"transactionHash\":\"0x46dbdcd5cfe1353696edfd1ed2e3df2dc3626126b871a0b00e5b135d5aac6136\",\"type\":\"0x00\",\"cumulativeGasUsed\":\"0x07270e00\",\"bloom\":\"0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\",\"logs\":[],\"status\":false,\"rlp\":\"0xf9010a808407270e00b9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c0\"},\"txbytes\":\"0xf861800a8407270e009493b696170807b34317f5e2ec7151fd738b6e4125808026a068c9efbc58f00d04f0dd4ddfa32c38a9f4ca22b854bd2c6ad920d71c44bb1e84a037416e1afcf08e351e22665ae8ac5000a5b041f5f8a1024c70951463a91699ab\",\"indexes\":{\"data\":0,\"gas\":0,\"value\":0},\"state\":{\"0x93b696170807b34317f5e2ec7151fd738b6e4125\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x90600055\",\"storage\":{}},\"0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff\":{\"nonce\":\"0x01\",\"balance\":\"0x033b2e3c9fd0803ca0797400\",\"code\":\"0x\",\"storage\":{}},\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\":{\"nonce\":\"0x00\",\"balance\":\"0x47868c00\",\"code\":\"0x\",\"storage\":{}}}}]},\"config\":{\"chainid\":\"0x01\"}}}"

  /** The same fixture with one octet of its published receipt altered, inside
    * the gas the receipt states and nowhere else.
    *
    * ==Altered in the `rlp` alone, which is the point of the case==
    *
    * The receipt object states its gas twice, once as its own member and once
    * inside the published octets, and only the second is touched here. A reader
    * that had taken the members instead would agree with this fixture, so the
    * divergence is evidence about WHICH of the two is being compared.
    */
  private val alteredReceipt: String =
    receiptFixture.replace("82a02eb90100", "82a02fb90100")

  private val stateFixture: String =
    """{
      |  "createInitOOGforCREATE": {
      |    "env": {
      |      "currentCoinbase": "0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba",
      |      "currentDifficulty": "0x020000",
      |      "currentGasLimit": "0x05f5e100",
      |      "currentNumber": "0x01",
      |      "currentTimestamp": "0x03e8"
      |    },
      |    "pre": {
      |      "0x095e7baea6a6c7c4c2dfeb977efac326af552d87": {
      |        "balance": "0x0de0b6b3a7640000",
      |        "code": "0x605a600053600160006001f0ff",
      |        "nonce": "0x00",
      |        "storage": {}
      |      },
      |      "0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b": {
      |        "balance": "0x0de0b6b3a7640000",
      |        "code": "0x",
      |        "nonce": "0x00",
      |        "storage": {}
      |      }
      |    },
      |    "transaction": {
      |      "data": [ "0x" ],
      |      "gasLimit": [ "0xcf1c" ],
      |      "gasPrice": "0x01",
      |      "nonce": "0x00",
      |      "sender": "0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b",
      |      "to": "0x095e7baea6a6c7c4c2dfeb977efac326af552d87",
      |      "value": [ "0x0186a0" ]
      |    },
      |    "post": {
      |      "Frontier": [
      |        {
      |          "hash": "0xb61e4a95fae40806b0ddef0883479c3db70e79e019ab4260535560827525c00c",
      |          "indexes": { "data": 0, "gas": 0, "value": 0 },
      |          "logs": "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"
      |        }
      |      ]
      |    }
      |  }
      |}""".stripMargin

  private def vmVerdict(contents: String): Verdict =
    VmFixture.decodeFile("calibration", contents) match
      case Left(error)     => Verdict.Diverged(Vector(error))
      case Right(fixtures) =>
        fixtures
          .map(fixture => VmFixtureRunner.run(fixture, ethereum.Upgrades.frontier.evm))
          .headOption
          .getOrElse(Verdict.Diverged(Vector("no case")))

  /** The network this fixture was filled for: it carries `"chainid":"0x01"`,
    * and every published signature in it was made against that identifier.
    */
  private val filledFor: UInt64 = ethereum.Mainnet.network.chainId

  /** One published case run at `rules`, reading the expectations it files under
    * `fork`.
    *
    * **The two have to name the same fork and nothing here checks it**, which is
    * the harness's own version of the hazard `StateFixtureRunner.run` states:
    * a case read under a fork whose section it does not carry yields no case at
    * all, and a comparison over no case is not a comparison. Every assertion
    * below is written so that it fails on that rather than passing.
    */
  private def stateVerdict(
      contents: String,
      rules: UpgradeRules = ethereum.Upgrades.frontier,
      chainId: UInt64 = filledFor,
      fork: String = StateFixture.Fork
  ): Verdict =
    StateFixture.decodeFile("calibration", contents, fork) match
      case Left(error)     => Verdict.Diverged(Vector(error))
      case Right(contents) =>
        contents.fixtures
          .map(fixture => StateFixtureRunner.run(fixture, chainId, rules))
          .headOption
          .getOrElse(Verdict.Diverged(Vector("no case")))

  /** The published signature with an `s` above half the curve order.
    *
    * **Not the true mirror image of the published one** -- computing `n - s`
    * was not done, and claiming it would be a number this file asserts and never
    * checked. What is true and is all the bound needs: this `s` begins `0xa6`,
    * half the order begins `0x7f`, so it is above. Written as a substitution so
    * the rest of the fixture stays verbatim.
    */
  private val highSignature: String =
    rejectionFixture
      .replace("1ba0", "1ca0")
      .replace(
        "59d5c99684510838ebaea7f337bc9599364414b12acbde5223ed0d265896fa48",
        "a62a366897aef7c714515080cc8436a6c9bbeb4e0b3fa5add5fd7d5c98d3e6f9"
      )

  private val atByzantium: String = "Byzantium"

  private def byzantiumVerdict(contents: String, rules: UpgradeRules): Verdict =
    stateVerdict(contents, rules, filledFor, atByzantium)

  private def diverges(verdict: Verdict): Boolean = verdict match
    case Verdict.Diverged(_) => true
    case _                   => false

  /** What a verdict disagreed about, so a case can say the disagreement was
    * about one thing and not merely that there was one.
    */
  private def reasonsOf(verdict: Verdict): Vector[String] = verdict match
    case Verdict.Diverged(reasons) => reasons
    case _                         => Vector.empty

  "a published VM fixture" should "agree with the interpreter" in
    assert(vmVerdict(vmFixture) == Verdict.Agreed, vmVerdict(vmFixture).toString)

  it should "diverge when its expected remaining gas is altered by one" in
    assert(diverges(vmVerdict(vmFixture.replace("\"gas\": \"0x013874\"", "\"gas\": \"0x013875\""))))

  it should "diverge when its expected storage value is altered" in {
    val altered = vmFixture.replace(
      "\"0x00\": \"0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe\"",
      "\"0x00\": \"0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd\""
    )
    assert(diverges(vmVerdict(altered)))
  }

  it should "diverge when its expected output is altered" in
    assert(diverges(vmVerdict(vmFixture.replace("\"out\": \"0x\"", "\"out\": \"0x01\""))))

  it should "diverge when its expected logs digest is altered" in {
    val altered = vmFixture.replace(
      "\"logs\": \"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\"",
      "\"logs\": \"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49348\""
    )
    assert(diverges(vmVerdict(altered)))
  }

  it should "diverge when its expected post balance is altered" in {
    val altered = vmFixture.replaceFirst("0x0de0b6b3a7640000", "0x0de0b6b3a7640001")
    assert(diverges(vmVerdict(altered)))
  }

  "a published state fixture" should "agree with the machine and the transaction layer" in
    assert(stateVerdict(stateFixture) == Verdict.Agreed, stateVerdict(stateFixture).toString)

  it should "diverge when the expected state root is altered by one nibble" in {
    val altered = stateFixture.replace(
      "0xb61e4a95fae40806b0ddef0883479c3db70e79e019ab4260535560827525c00c",
      "0xb61e4a95fae40806b0ddef0883479c3db70e79e019ab4260535560827525c00d"
    )
    assert(diverges(stateVerdict(altered)))
  }

  it should "diverge when the pre-state the transaction runs against is altered" in {
    val altered = stateFixture.replaceFirst("0x0de0b6b3a7640000", "0x0de0b6b3a7640001")
    assert(diverges(stateVerdict(altered)))
  }

  it should "diverge when the transaction's gas limit is altered" in
    assert(diverges(stateVerdict(stateFixture.replace("\"0xcf1c\"", "\"0xcf1d\""))))

  "a published fixture whose transaction is refused" should "agree with the layer that refuses it" in
    assert(stateVerdict(rejectionFixture) == Verdict.Agreed, stateVerdict(rejectionFixture).toString)

  it should "diverge when the sender is funded enough for the rejection to stop happening" in {
    // The fixture rejects for insufficient funds. Fund the sender and the
    // transaction is admitted instead, so the post state and its root no longer
    // match what the fixture expects. This is what pins the balance branch of
    // `admit`: remove that branch and this case stops diverging.
    val altered = rejectionFixture.replace("\"balance\":\"0x00\"", "\"balance\":\"0x0de0b6b3a7640000\"")
    // Bound away rather than dropped: this guards that the replacement actually
    // matched, so the test cannot pass by altering nothing. The ratchet treats an
    // unused Assertion as an error, and only the last one is the test's claim.
    val _ = assert(altered != rejectionFixture, "the balance the fixture withholds was not found to alter")
    assert(diverges(stateVerdict(altered)))
  }

  it should "be executed rather than set aside when the transaction deploys code" in {
    // Rewriting the recipient away turns this into a creation. The published
    // root is a call's, so the run must diverge -- and diverging is the claim:
    // the case is now judged, where it was previously counted as unexamined.
    val altered = stateFixture.replace("\"to\": \"0x095e7baea6a6c7c4c2dfeb977efac326af552d87\"", "\"to\": \"\"")
    val _ = assert(altered != stateFixture, "the recipient this test rewrites was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "diverge when the fixture names a refusal other than the one admission makes" in {
    // Renaming the expectation leaves the pre-state, and so the state root,
    // exactly as published. The transaction is still refused and still refused
    // at the same branch, so every other check on this case agrees -- which is
    // what makes the reason comparison the only thing that can see it.
    val altered = rejectionFixture.replace("INSUFFICIENT_ACCOUNT_FUNDS", "NONCE_IS_MAX")
    val _ = assert(altered != rejectionFixture, "the expectation this test rewrites was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "diverge when the fixture names a refusal this build cannot make" in {
    // The state every rule is in before the fork introducing it lands: the
    // corpus names it, admission has no branch for it, and a refusal for an
    // unrelated reason leaves the root where the fixture expects it. A build
    // missing the rule has to fail here rather than be credited with it.
    val altered = rejectionFixture.replace("INSUFFICIENT_ACCOUNT_FUNDS", "INVALID_SIGNATURE_VRS")
    val _ = assert(altered != rejectionFixture, "the expectation this test rewrites was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "diverge when the fixture expects execution and admission refuses" in {
    val altered =
      rejectionFixture.replace(",\"expectException\":\"TransactionException.INSUFFICIENT_ACCOUNT_FUNDS\"", "")
    val _ = assert(altered != rejectionFixture, "the expectation this test removes was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "refuse the transaction when the published signature names no scheme" in {
    // `1b` is the v the fixture signed with. Moving it off 27 and 28 leaves a
    // value no legacy scheme reads and no chain identifier recovers, so the
    // signature names no account at all. Under a runner that takes the stated
    // sender on trust this edit changes nothing whatever -- which is what makes
    // it the case that pins recovery to being used.
    val altered = rejectionFixture.replace("80801ba0", "80801da0")
    val _ = assert(altered != rejectionFixture, "the signature byte this test rewrites was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "be reported as undecodable when the published signature does not decode" in {
    // Valid hex that is not a transaction. The fixture is otherwise intact, so
    // the run must report that it could not read the signature rather than fall
    // back to the sender the file names.
    val altered = rejectionFixture.replace("0xf860800a", "0xc0800a")
    val _ = assert(altered != rejectionFixture, "the signature prefix this test rewrites was not found")
    assert(stateVerdict(altered).toString.contains("Undecodable"), stateVerdict(altered).toString)
  }

  it should "admit a signature at Frontier that the bound would refuse" in {
    // The mirror image recovers an account too -- a different one, unfunded, so
    // the fixture's own balance rule refuses it. The point is that admission got
    // that far: nothing about the signature stopped it.
    val _ = assert(highSignature != rejectionFixture, "the signature this test rewrites was not found")
    assert(
      stateVerdict(highSignature) == Verdict.Agreed,
      stateVerdict(highSignature).toString
    )
  }

  it should "refuse that same signature once the bound is applied" in {
    // Same bytes, same expectation, one rule different. The fixture expects a
    // refusal for want of funds and now gets one for the signature -- so both
    // runs refuse, and a check comparing only WHETHER the case was refused
    // agrees with the fixture here. Only comparing the reason reports it.
    val unbounded = ethereum.Upgrades.frontier
    val bounded = unbounded.copy(admission = Eip2.lowSignatureS(unbounded.admission))
    assert(diverges(stateVerdict(highSignature, bounded)), stateVerdict(highSignature, bounded).toString)
  }

  it should "be admitted just the same when a different network asks" in
    // Its signature is unprotected -- `1b` is a `v` of 27 -- so it names no
    // chain identifier and is valid on every network by construction. The
    // identifier is threaded through the whole harness and reaches admission's
    // comparison; this is the case that comparison must NOT decide, and a
    // comparison that treated an absent identifier as a mismatch would refuse
    // every legacy transaction ever signed before EIP-155.
    assert(
      stateVerdict(rejectionFixture, chainId = ethereumclassic.Mainnet.network.chainId) == Verdict.Agreed,
      stateVerdict(rejectionFixture, chainId = ethereumclassic.Mainnet.network.chainId).toString
    )

  "a published fixture carrying a receipt" should "agree with the receipt the settlement layer builds" in
    // Byte for byte against the octets the file publishes, which is what a
    // receipts trie stores -- so this reaches the gas the receipt states, its
    // bloom, its logs and its format as well as the field the fork settles.
    assert(stateVerdict(receiptFixture) == Verdict.Agreed, stateVerdict(receiptFixture).toString)

  it should "diverge when one octet inside the published receipt is altered" in
    // The mutation is inside `rlp` and nowhere else, so a comparison that had
    // read the receipt's own members would still agree. What this establishes
    // is that the octets are what is being compared.
    assert(diverges(stateVerdict(alteredReceipt)), "the published receipt's octets are not what the verdict compares")

  it should "name the field the altered octet sits in" in
    // A byte comparison that could only say *these differ* would be a check
    // nobody can act on, so the report decodes the published receipt and says
    // which field moved.
    assert(
      reasonsOf(stateVerdict(alteredReceipt)).exists(_.startsWith("receipt: cumulative gas ")),
      reasonsOf(stateVerdict(alteredReceipt)).mkString("; ")
    )

  "the same published test filled for the fork that replaces the field" should
    "agree at rules whose receipts carry a status" in
    assert(
      byzantiumVerdict(statusReceiptFixture, ethereum.Upgrades.byzantium) == Verdict.Agreed,
      byzantiumVerdict(statusReceiptFixture, ethereum.Upgrades.byzantium).toString
    )

  it should "diverge at the rules one upgrade below, on the replaced field and nothing else" in {
    // The whole of EIP-658, isolated. The upgrade below adopts the same
    // clearing rule and the same prices, and this case reaches none of the
    // three operations the later one adds -- so the state it leaves is the same
    // state and the only thing that can disagree is the field the document
    // replaces. A harness not comparing receipts reports this as agreement.
    //
    // The count is asserted with the wording rather than beside it: a reason
    // list of length one is also what a case that never decoded produces, so
    // the length alone is satisfied by the run that measured nothing.
    val reasons = reasonsOf(byzantiumVerdict(statusReceiptFixture, ethereum.Upgrades.spuriousDragon))
    assert(
      reasons.length == 1 && reasons.head.startsWith("receipt: first field PostState("),
      reasons.mkString("; ")
    )
  }

  "a published fixture whose transaction failed" should "agree at rules whose receipts carry a status" in
    // The other status, read from published octets rather than from a value
    // chosen here: the receipt states `"status": false` and carries it as the
    // empty string, which is the encoding a receipts root commits to.
    assert(
      byzantiumVerdict(failedReceiptFixture, ethereum.Upgrades.byzantium) == Verdict.Agreed,
      byzantiumVerdict(failedReceiptFixture, ethereum.Upgrades.byzantium).toString
    )

  it should "diverge at the rules one upgrade below, on that same field and nothing else" in {
    val reasons = reasonsOf(byzantiumVerdict(failedReceiptFixture, ethereum.Upgrades.spuriousDragon))
    assert(
      reasons.length == 1 && reasons.head.startsWith("receipt: first field PostState("),
      reasons.mkString("; ")
    )
  }

  "the boundary around one case" should "pass a verdict that did not throw through unchanged" in
    // The negative control. Without it a boundary that reported a divergence
    // unconditionally would satisfy every case below.
    assert(
      CertificationCorpora.outcomeOf("quiet")(Verdict.Agreed) == CaseOutcome("quiet", Verdict.Agreed),
      "the boundary reported something other than what running the case returned"
    )

  it should "record a case that threw as a divergence rather than as a skip" in {
    // A skip means there was nothing here to compare; a throw means the machine
    // broke on something there was. A harness conflating them reports a machine
    // that throws on every case as entirely skipped, and therefore green.
    val outcome = CertificationCorpora.outcomeOf("broken")(throw new IllegalStateException("boom"))
    val diverged = outcome.verdict match
      case Verdict.Diverged(_) => true
      case _                   => false
    assert(diverged, "a case that threw was not recorded as a divergence")
  }

  it should "name what broke in the divergence it records" in {
    val outcome = CertificationCorpora.outcomeOf("broken")(throw new IllegalStateException("boom"))
    val reasons = outcome.verdict match
      case Verdict.Diverged(causes) => causes.mkString
      case _                        => ""
    assert(
      reasons.contains("IllegalStateException") && reasons.contains("boom"),
      "the divergence does not say what broke, which is what makes a throwing case diagnosable at all"
    )
  }

  it should "let a fatal error through rather than recording it as a wrong answer" in {
    // A machine that has run out of memory has not disagreed with a fixture.
    // Recording that as a divergence would turn an exhausted runtime into a
    // consensus finding, which is why the boundary is NonFatal and not Throwable.
    val propagated =
      try
        val _ = CertificationCorpora.outcomeOf("fatal")(throw new OutOfMemoryError("calibration"))
        false
      catch case _: OutOfMemoryError => true
    assert(propagated, "a fatal error was swallowed and recorded as a case outcome")
  }
