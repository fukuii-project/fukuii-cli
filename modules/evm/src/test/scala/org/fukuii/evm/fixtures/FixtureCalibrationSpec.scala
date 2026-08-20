package org.fukuii.evm.fixtures

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.{ChainRules, Proposals}

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
    * Every other case here uses a fixture that is admitted, so
    * `FrontierTransaction.admit`'s six rejection branches -- and the comparison
    * branch that reads `expectException` -- were exercised by nothing that runs
    * without the corpus. Dropping a rejection branch would have failed no test
    * a clone can run.
    *
    * Taken verbatim from
    * `state_tests/for_frontier/frontier/validation/transaction/sender_balance_insufficient_state_test.json`
    * at the `tests@v20.0.1` release, compacted only in whitespace.
    */
  private val rejectionFixture: String =
    "{\"tests/frontier/validation/test_transaction.py::test_sender_balance_insufficient_state_test[fork_Frontier-state_test]\":{\"env\":{\"currentCoinbase\":\"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba\",\"currentGasLimit\":\"0x07270e00\",\"currentNumber\":\"0x01\",\"currentTimestamp\":\"0x03e8\",\"currentDifficulty\":\"0x020000\"},\"pre\":{\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"transaction\":{\"nonce\":\"0x00\",\"gasPrice\":\"0x0a\",\"gasLimit\":[\"0x0186a0\"],\"to\":\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\",\"value\":[\"0x00\"],\"data\":[\"0x\"],\"sender\":\"0xc0f6dc9e5836f54caadbf59cc69346c508e1992b\",\"secretKey\":\"0x4a2ffc8867fd8d1773481cf13f36e44f033133c579520d2745e46c3bbbf21e6a\"},\"post\":{\"Frontier\":[{\"hash\":\"0x7d2b9aab793f618eeb1773120fd525386b788a3947bd47ec6a7cfea9e51d1b6d\",\"logs\":\"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347\",\"txbytes\":\"0xf860800a830186a09443e6f595ac8e8c6e584c7a2d9949a6bfae8c428780801ba02dda4459c3a9ad10958d14dfb46995f67a72daf0dae7a59ec01282848b13c969a059d5c99684510838ebaea7f337bc9599364414b12acbde5223ed0d265896fa48\",\"indexes\":{\"data\":0,\"gas\":0,\"value\":0},\"state\":{\"0x43e6f595ac8e8c6e584c7a2d9949a6bfae8c4287\":{\"nonce\":\"0x01\",\"balance\":\"0x00\",\"code\":\"0x600160005500\",\"storage\":{}}},\"expectException\":\"TransactionException.INSUFFICIENT_ACCOUNT_FUNDS\"}]},\"config\":{\"chainid\":\"0x01\"},\"_info\":{\"hash\":\"0xcbdc4bcefca612f316909b9b2134198a359a51ac8a0c178b2eafd9d9d16d8845\",\"fixture-format\":\"state_test\",\"comment\":\"`execution-specs` generated test\",\"filling-transition-tool\":\"2.19.0\",\"description\":\"A legacy transaction from a sender that cannot afford `gas * gasPrice`\\nmust be rejected, exercised through the state-test code path.\",\"url\":\"https://github.com/ethereum/execution-specs/blob/87aba1a38a476b31f819a2390eb481527e6dc683/tests/frontier/validation/test_transaction.py#L208\",\"metadata\":{\"opcode_count\":{}}}}}"

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
        fixtures.map(VmFixtureRunner.run).headOption.getOrElse(Verdict.Diverged(Vector("no case")))

  private def stateVerdict(
      contents: String,
      rules: ChainRules = StateFixtureRunner.Baseline
  ): Verdict =
    StateFixture.decodeFile("calibration", contents) match
      case Left(error)     => Verdict.Diverged(Vector(error))
      case Right(contents) =>
        contents.fixtures
          .map(fixture => StateFixtureRunner.run(fixture, rules))
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

  private def diverges(verdict: Verdict): Boolean = verdict match
    case Verdict.Diverged(_) => true
    case _                   => false

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

  "a published state fixture" should "agree with the machine and the transaction driver" in
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

  "a published fixture whose transaction is rejected" should "agree with the driver" in
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

  it should "diverge when the fixture names a refusal other than the one the driver makes" in {
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

  it should "diverge when the fixture expects execution and the driver refuses" in {
    val altered =
      rejectionFixture.replace(",\"expectException\":\"TransactionException.INSUFFICIENT_ACCOUNT_FUNDS\"", "")
    val _ = assert(altered != rejectionFixture, "the expectation this test removes was not found")
    assert(diverges(stateVerdict(altered)))
  }

  it should "refuse the transaction when the published signature names no scheme" in {
    // `1b` is the v the fixture signed with. Moving it off 27 and 28 leaves a
    // value no legacy scheme reads and no chain identifier recovers, so the
    // signature names no account at all. Under a driver that takes the stated
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

  it should "admit a signature at the baseline that the bound would refuse" in {
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
    // refusal for want of funds and now gets one for the signature, so the
    // reason comparison is what reports it -- which is why R179 had to land
    // before this could be tested at all.
    val bounded = StateFixtureRunner.Baseline.applying(Proposals.lowSignatureS)
    assert(diverges(stateVerdict(highSignature, bounded)), stateVerdict(highSignature, bounded).toString)
  }
