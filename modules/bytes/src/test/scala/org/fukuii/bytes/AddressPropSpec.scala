package org.fukuii.bytes

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Tabulated address vectors. Holds no fixed examples of its own; the
  * example-shaped cases live in [[AddressSpec]].
  *
  * The accept-and-reject rows are go-ethereum's, from `TestAddressUnmarshalJSON`
  * in `common/types_test.go` @ `7a1b11564c16f54dff0a2f578179c482d9f701bf`, which
  * exercises that client's own strict address parse. Two of its rows are omitted
  * rather than adapted: it drives JSON, so its empty-string and bare-quotes cases
  * test the JSON layer, and this module has none.
  *
  * The published addresses are the four in ERC-55
  * @ `2b5baad95598defa4eaf18fc0674a2675b378b57`, lowercased. This module computes
  * no digest, so the mixed-case checksum those vectors also encode is not
  * testable here and is not tested here.
  */
class AddressPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val strictParseCases = Table(
    ("input", "shouldParse", "why"),
    ("0x", false, "no bytes at all"),
    ("0x00", false, "one byte, not twenty"),
    ("0xG000000000000000000000000000000000000000", false, "G is not a hex digit"),
    ("0x0000000000000000000000000000000000000000", true, "the zero address is twenty valid bytes"),
    ("0x0000000000000000000000000000000000000010", true, "a small value is still twenty bytes")
  )

  private val publishedAddresses = Table(
    "lowercaseHex",
    "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed",
    "fb6916095ca1df60bb79ce92ce3ea74c37c5d359",
    "dbf03b407c01e7cd3cbea99509d93f8dddc8c6fb",
    "d1220a0cf47c7b9be7a2e6ba89f429762e7b9adb"
  )

  property("Address.fromHex agrees with the reference client's strict parse") {
    forAll(strictParseCases) { (input: String, shouldParse: Boolean, why: String) =>
      assert(Address.fromHex(input).isRight == shouldParse, input + " — " + why)
    }
  }

  property("Address.fromHex round-trips every published address") {
    forAll(publishedAddresses) { (lowercaseHex: String) =>
      assert(Address.fromHex(lowercaseHex).map(_.toHex) == Right(lowercaseHex), "round trip must be exact")
    }
  }

  property("a 0x prefix does not change the parsed address") {
    forAll(publishedAddresses) { (lowercaseHex: String) =>
      assert(
        Address.fromHex("0x" + lowercaseHex).map(_.toHex) == Address.fromHex(lowercaseHex).map(_.toHex),
        "the prefix is accepted and discarded"
      )
    }
  }
