package org.fukuii.evm.fixtures

import org.scalatest.flatspec.AnyFlatSpec

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

  private def stateVerdict(contents: String): Verdict =
    StateFixture.decodeFile("calibration", contents) match
      case Left(error)     => Verdict.Diverged(Vector(error))
      case Right(contents) =>
        contents.fixtures.map(StateFixtureRunner.run).headOption.getOrElse(Verdict.Diverged(Vector("no case")))

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

  it should "be reported as skipped when the transaction deploys code" in {
    val altered = stateFixture.replace("\"to\": \"0x095e7baea6a6c7c4c2dfeb977efac326af552d87\"", "\"to\": \"\"")
    assert(stateVerdict(altered) == Verdict.Skipped(SkipReason.TransactionLevelCreation))
  }
