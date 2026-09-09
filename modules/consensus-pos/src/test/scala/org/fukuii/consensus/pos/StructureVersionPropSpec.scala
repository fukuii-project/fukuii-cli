package org.fukuii.consensus.pos

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The ladder both appended chains climb, as a table over every version that
  * has activated.
  *
  * ==What this pins that the two unit suites do not==
  *
  * They assert each rung on its own. This asserts the property that makes the
  * chain the right representation at all: **the versions are strict prefixes**,
  * so a field present at one version is present at every version above it, and
  * the count of present fields is the version. A representation of independent
  * options would satisfy every assertion in the unit suites and fail these.
  *
  * ==Where the rungs come from==
  *
  * The specification's own structure definitions at `ethereum/execution-apis` @
  * `6570b55`: `ExecutionPayloadV1` at `src/engine/paris.md:41-58`,
  * `ExecutionPayloadV2` at `src/engine/shanghai.md:54-72`,
  * `ExecutionPayloadV3` at `src/engine/cancun.md:41-61`, and
  * `PayloadAttributesV1` at `src/engine/paris.md:70-76`,
  * `PayloadAttributesV2` at `src/engine/shanghai.md:79-86`,
  * `PayloadAttributesV3` at `src/engine/cancun.md:80-88`.
  *
  * The corresponding rows of the published engine corpus agree by measurement
  * rather than by reading: over `blockchain_tests_engine` at `tests-v20.0.1`,
  * sampling roughly 870 payload entries under each of those five fork labels,
  * the withdrawal field is absent from every Paris entry and present in every
  * entry of the four later samples, and the two blob-gas fields are absent from
  * every Paris and Shanghai entry and present in every entry of the three later
  * samples. The transition and blob-parameter labels were not sampled.
  *
  * **That corpus is not wired into this build yet**, so these rows are the
  * specification's, and the measurement is why they are trusted rather than
  * what checks them.
  */
class StructureVersionPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val payloads = Table(
    ("which structure the specification calls it", "payload", "version", "withdrawals", "blobGas"),
    ("ExecutionPayloadV1", PosFixtures.bareCore, 1, false, false),
    ("ExecutionPayloadV2", PosFixtures.withWithdrawals, 2, true, false),
    ("ExecutionPayloadV3", PosFixtures.withBlobGas, 3, true, true)
  )

  private val attributes = Table(
    ("which structure the specification calls it", "attributes", "version", "withdrawals", "beaconRoot"),
    ("PayloadAttributesV1", PosFixtures.bareAttributes, 1, false, false),
    ("PayloadAttributesV2", PosFixtures.attributesWithWithdrawals, 2, true, false),
    ("PayloadAttributesV3", PosFixtures.attributesWithBeaconRoot, 3, true, true)
  )

  property("a payload reports the structure version its appended chain describes") {
    forAll(payloads) { (name: String, payload: ExecutionPayload, version: Int, _: Boolean, _: Boolean) =>
      assert(payload.structureVersion == version, name + " reported a version its chain does not describe")
    }
  }

  property("a payload carries the withdrawal list at exactly the versions that define it") {
    forAll(payloads) { (name: String, payload: ExecutionPayload, _: Int, withdrawals: Boolean, _: Boolean) =>
      assert(payload.withdrawals.isDefined == withdrawals, name + " disagreed with the specification about EIP-4895")
    }
  }

  property("a payload carries both blob-gas fields at exactly the versions that define them") {
    forAll(payloads) { (name: String, payload: ExecutionPayload, _: Int, _: Boolean, blobGas: Boolean) =>
      assert(
        payload.blobGasUsed.isDefined == blobGas && payload.excessBlobGas.isDefined == blobGas,
        name + " carried one half of EIP-4844's pair, which no version defines"
      )
    }
  }

  property("a build request reports the structure version its appended chain describes") {
    forAll(attributes) { (name: String, attrs: PayloadAttributes, version: Int, _: Boolean, _: Boolean) =>
      assert(attrs.structureVersion == version, name + " reported a version its chain does not describe")
    }
  }

  property("a build request carries the withdrawal list at exactly the versions that define it") {
    forAll(attributes) { (name: String, attrs: PayloadAttributes, _: Int, withdrawals: Boolean, _: Boolean) =>
      assert(attrs.withdrawals.isDefined == withdrawals, name + " disagreed with the specification about EIP-4895")
    }
  }

  property("a build request carries the parent beacon root at exactly the versions that define it") {
    forAll(attributes) { (name: String, attrs: PayloadAttributes, _: Int, _: Boolean, beaconRoot: Boolean) =>
      assert(
        attrs.parentBeaconBlockRoot.isDefined == beaconRoot,
        name + " disagreed with the specification about EIP-4788"
      )
    }
  }

  property("a payload's present-field count is its structure version less one") {
    forAll(payloads) { (name: String, payload: ExecutionPayload, version: Int, _: Boolean, _: Boolean) =>
      assert(
        payload.withdrawals.size + payload.blobGasUsed.size == version - 1,
        name + " has a field count the prefix property forbids, so the chain admitted a gap"
      )
    }
  }

  property("a build request's present-field count is its structure version less one") {
    forAll(attributes) { (name: String, attrs: PayloadAttributes, version: Int, _: Boolean, _: Boolean) =>
      assert(
        attrs.withdrawals.size + attrs.parentBeaconBlockRoot.size == version - 1,
        name + " has a field count the prefix property forbids, so the chain admitted a gap"
      )
    }
  }
