package org.fukuii.evm.fixtures

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.types.TransactionType

/** What the reader makes of one combination of a state case's arrays: which
  * format it names, what it takes as the declaration that combination makes,
  * and what it takes as the receipt the case published.
  *
  * ==Why this is worth its own suite==
  *
  * The format decides whether a transaction is refused for its envelope before
  * anything else happens to it, so a reader that names the wrong one reports a
  * refusal the fixture never asked for -- or executes a case the fixture
  * expects to be refused. Neither shows up as a reader fault: it shows up as
  * the machine disagreeing with the corpus, which is the one thing a
  * certification run is supposed to mean.
  *
  * The receipt fails the other way and is worse for it. A reader that finds no
  * receipt reports a case with nothing to compare, which is indistinguishable
  * from a corpus that published none -- so the check does not fail, it stops
  * existing. The cases below are what separate the two.
  *
  * The declaration fails a third way: it is priced before anything runs, so a
  * reader that reads it short charges a transaction less than the chain charged
  * it and settles to another root -- and it does that only on the combinations
  * that declare something, so a tier wired without it passes on most of its
  * entries.
  *
  * ==None of these cases is run, so nothing here is an expectation==
  *
  * Each is decoded and its transaction's format read. The state root every case
  * carries is a syntactic requirement of the shape and is deliberately written
  * as zero, which no run produces -- a plausible-looking root here would read as
  * a claim about a state nobody computed.
  *
  * The transaction bodies are modelled on the mixed shape the published corpora
  * actually carry -- `ethereum/legacytests` @ `1f581b8c`
  * `Cancun/GeneralStateTests/stEIP2930/coinbaseT01.json`, whose `accessLists`
  * is `[null, [...], [...]]` against three `data` entries, and whose per-entry
  * `txbytes` begins `0xf8` at index 0 and `0x01` at indexes 1 and 2. That file
  * is in a directory this build does not read, and no case in the four corpora
  * it does read carries a null entry, so the shape is reproduced here rather
  * than quoted from material the suite can reach.
  */
class StateFixtureSpec extends AnyFlatSpec:

  private def fixture(transaction: String, entries: String): String =
    """{ "classified": {
      |  "env": {
      |    "currentCoinbase": "0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba",
      |    "currentDifficulty": "0x020000",
      |    "currentGasLimit": "0x05f5e100",
      |    "currentNumber": "0x01",
      |    "currentTimestamp": "0x03e8"
      |  },
      |  "pre": {
      |    "0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b": {
      |      "balance": "0x0de0b6b3a7640000", "code": "0x", "nonce": "0x00", "storage": {}
      |    }
      |  },
      |  "transaction": { TRANSACTION },
      |  "post": { "Frontier": [ ENTRIES ] }
      |} }""".stripMargin
      .replace("TRANSACTION", transaction)
      .replace("ENTRIES", entries)

  /** The fields every transaction body here shares, so that a body below states
    * only what decides its format.
    */
  private val common: String =
    """"nonce": "0x00",
      |"gasPrice": "0x0a",
      |"gasLimit": [ "0x0186a0" ],
      |"to": "0x095e7baea6a6c7c4c2dfeb977efac326af552d87",
      |"value": [ "0x00" ],
      |"data": [ "0x", "0x", "0x" ],
      |"sender": "0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b"""".stripMargin

  private val zeroRoot: String = "0x" + "00" * 32

  /** One post entry per `data` index, with no published bytes, so the fields
    * are what the reader has to decide from.
    */
  private val threeUnsignedEntries: String =
    (0 to 2)
      .map(index => s"""{ "hash": "$zeroRoot", "indexes": { "data": $index, "gas": 0, "value": 0 } }""")
      .mkString(",")

  private def signedEntry(bytes: String): String =
    s"""{ "hash": "$zeroRoot", "indexes": { "data": 0, "gas": 0, "value": 0 }, "txbytes": "$bytes" }"""

  private val unsignedEntry: String =
    s"""{ "hash": "$zeroRoot", "indexes": { "data": 0, "gas": 0, "value": 0 } }"""

  /** A case whose access lists are null at one index and present at the others,
    * which is the shape the field's presence cannot express.
    */
  private val mixedAccessLists: String =
    fixture(
      common + ""","accessLists": [ null, [], [ { "address": "0x0000000000000000000000000000000000003000",
        |"storageKeys": [] } ] ]""".stripMargin,
      threeUnsignedEntries
    )

  /** A case whose only distinguishing content is `extra`, publishing no bytes,
    * so the fields are the whole of what the reader has to decide from.
    *
    * Publishing bytes here would put the envelope in charge, and every case
    * built on this would be exercising that path rather than the ordering it
    * names.
    */
  private def statingOnly(extra: String): String =
    fixture(common + "," + extra, unsignedEntry)

  /** The format the reader named for one combination, or nothing when the case
    * did not decode or holds no such combination.
    *
    * Nothing rather than a default: a fixture this suite got wrong must not
    * read as a format the reader chose.
    */
  private def kindAt(contents: String, data: Int): Option[TransactionType] =
    StateFixture
      .decodeFile("classification", contents)
      .toOption
      .flatMap(_.fixtures.find(_.name.endsWith("[d" + data + "g0v0]")))
      .map(_.transaction.kind)

  /** What the reader made of the whole case, for the clue on a failure. */
  private def decoded(contents: String): String =
    StateFixture
      .decodeFile("classification", contents)
      .fold(identity, _.fixtures.map(f => f.name + "=" + f.transaction.kind).mkString(" "))

  /** A case carrying an access list at index 0 and published bytes that
    * contradict it, so only one of the two can be what the reader used.
    */
  private def envelope(bytes: String): String =
    fixture(common + ""","accessLists": [ [], [], [] ]""", signedEntry(bytes))

  /** A post entry publishing `receipt`, so a case can state one whose members
    * and octets say different things.
    */
  private def entryPublishing(receipt: String): String =
    s"""{ "hash": "$zeroRoot", "indexes": { "data": 0, "gas": 0, "value": 0 }, "receipt": $receipt }"""

  /** A case declaring an account with two slots at one index, an account with
    * none at another, and one account twice at a third.
    *
    * The repeat is the shape the proposal singles out: *"non-unique addresses
    * and storage keys are not disallowed, though they will be charged for
    * multiple times"*, so a reader that deduplicated would undercharge exactly
    * the case written to test that it does not.
    */
  private val declaringAccessLists: String =
    fixture(
      common + ""","accessLists": [
        |[ { "address": "0x0000000000000000000000000000000000003000",
        |    "storageKeys": [ "0x0000000000000000000000000000000000000000000000000000000000000001",
        |                     "0x0000000000000000000000000000000000000000000000000000000000000002" ] } ],
        |[ { "address": "0x0000000000000000000000000000000000004000" } ],
        |[ { "address": "0x0000000000000000000000000000000000005000", "storageKeys": [] },
        |  { "address": "0x0000000000000000000000000000000000005000", "storageKeys": [] } ] ]""".stripMargin,
      threeUnsignedEntries
    )

  /** What the reader read the declaration as for one combination, as the hex a
    * failure can be read from, or nothing where the file did not decode.
    */
  private def declaredAt(contents: String, data: Int): Option[Vector[(String, Vector[String])]] =
    StateFixture
      .decodeFile("classification", contents)
      .toOption
      .flatMap(_.fixtures.find(_.name.endsWith("[d" + data + "g0v0]")))
      .map(
        _.transaction.accessList.toVector.map(tuple => (tuple.address.toHex, tuple.storageKeys.toVector.map(_.toHex)))
      )

  /** What the reader expects of the first combination, or the reason the file
    * did not decode.
    */
  private def expectationIn(contents: String): Either[String, StateExpectation] =
    StateFixture
      .decodeFile("classification", contents)
      .flatMap(_.fixtures.headOption.map(_.expectation).toRight("the case yielded no combination"))

  /** The receipt octets the reader took, as hex, so a failure reads as a value
    * rather than as an array.
    */
  private def receiptIn(contents: String): Either[String, Option[String]] =
    expectationIn(contents).map(_.receipt.map(_.toHex))

  "an index whose access list is null" should "be read as the format predating the envelope" in
    // The whole of the defect: read from the field's presence, this index is an
    // access-list transaction, and a fork before EIP-2930 refuses the control
    // the case put beside its typed payloads.
    assert(kindAt(mixedAccessLists, 0) == Some(TransactionType.Legacy), decoded(mixedAccessLists))

  "an index whose access list is empty" should "be read as an access-list transaction" in
    // `[]` is not null. An empty list is a typed transaction listing nothing,
    // and collapsing the two would make the fix indistinguishable from naming
    // every index legacy.
    assert(kindAt(mixedAccessLists, 1) == Some(TransactionType.AccessList), decoded(mixedAccessLists))

  "an index whose access list names an address" should "be read as an access-list transaction" in
    assert(kindAt(mixedAccessLists, 2) == Some(TransactionType.AccessList), decoded(mixedAccessLists))

  "a case stating no access lists" should "be read as the format predating the envelope" in {
    val plain = fixture(common, threeUnsignedEntries)
    assert(kindAt(plain, 0) == Some(TransactionType.Legacy), decoded(plain))
  }

  "a case stating a maximum fee" should "be read as a fee-market transaction" in {
    val feeMarket = statingOnly(""""maxFeePerGas": "0x07", "maxPriorityFeePerGas": "0x01"""")
    assert(kindAt(feeMarket, 0) == Some(TransactionType.DynamicFee), decoded(feeMarket))
  }

  "a case stating both a maximum fee and an access list" should "be read as a fee-market transaction" in {
    // Both fields are stated together throughout the published corpora, because
    // a fee-market transaction may carry an access list. Whichever is tested
    // first decides, so the two orderings are distinguishable only here.
    val both = statingOnly(""""maxFeePerGas": "0x07", "accessLists": [ [], [], [] ]""")
    assert(kindAt(both, 0) == Some(TransactionType.DynamicFee), decoded(both))
  }

  "a case stating versioned hashes" should "be read as a blob transaction" in {
    // It states a maximum fee as well, because a blob transaction carries the
    // fee-market fields too. Deciding on those alone names this one fee-market,
    // which is what the ordering exists to prevent.
    val blob = statingOnly(
      """"maxFeePerGas": "0x07", "maxPriorityFeePerGas": "0x01",
        |"blobVersionedHashes": [ "0x0100000000000000000000000000000000000000000000000000000000000000" ]""".stripMargin
    )
    assert(kindAt(blob, 0) == Some(TransactionType.Blob), decoded(blob))
  }

  "a case stating an authorization list" should "be read as a set-code transaction" in {
    val setCode = statingOnly(
      """"maxFeePerGas": "0x07", "maxPriorityFeePerGas": "0x01", "authorizationList": []"""
    )
    assert(kindAt(setCode, 0) == Some(TransactionType.SetCode), decoded(setCode))
  }

  "published bytes beginning an RLP sequence" should "settle the format over fields naming an access list" in {
    // The fields say typed and the bytes say otherwise. The bytes are the
    // transaction; the fields are a convention about which of them a filler
    // wrote.
    val legacy = envelope("0xf8")
    assert(kindAt(legacy, 0) == Some(TransactionType.Legacy), decoded(legacy))
  }

  it should "read the shortest such sequence as one" in {
    // `0xc0` is the empty sequence and the lowest head there is, so it is where
    // the two RLP shapes meet. A bound one above it sends every empty sequence
    // back to the fields.
    val shortest = envelope("0xc0")
    assert(kindAt(shortest, 0) == Some(TransactionType.Legacy), decoded(shortest))
  }

  "published bytes beginning with a type tag" should "settle the format over fields naming none" in {
    val typed = fixture(common, signedEntry("0x01f8"))
    assert(kindAt(typed, 0) == Some(TransactionType.AccessList), decoded(typed))
  }

  it should "name the fee-market format for the tag EIP-1559 assigns" in {
    val typed = fixture(common, signedEntry("0x02f8"))
    assert(kindAt(typed, 0) == Some(TransactionType.DynamicFee), decoded(typed))
  }

  "published bytes beginning an RLP string" should "leave the format to the fields" in {
    // `0x80` heads a byte string, which is neither shape a transaction takes.
    // Resolving it here would answer with a format and report nothing; the
    // fields answer instead, and the bytes fail where they are decoded.
    val stringHead = envelope("0x80")
    assert(kindAt(stringHead, 0) == Some(TransactionType.AccessList), decoded(stringHead))
  }

  "a combination publishing a receipt" should "take its octets from the rlp member" in {
    // The correction this reader is written around. A receipt states whether
    // its transaction succeeded in a member whose NAME is the fork's answer --
    // `postState` before EIP-658 and `status` from it -- and states the same
    // thing again inside `rlp`. Here the two disagree on purpose, so a reader
    // that had keyed on either name answers differently from one that reads the
    // octets.
    val disagreeing =
      fixture(common, entryPublishing("""{ "postState": "0x", "status": true, "rlp": "0xc1c0" }"""))
    assert(receiptIn(disagreeing) == Right(Some("c1c0")), receiptIn(disagreeing).toString)
  }

  "a combination publishing no receipt" should "carry none rather than empty octets" in
    // The legacy tier publishes no receipt anywhere and this build reads it
    // three times, so absence is the ordinary case and not an edge one. Nothing
    // distinguishes a receipt of no bytes from a case that published none,
    // which is why the absence is modelled rather than defaulted.
    assert(receiptIn(fixture(common, unsignedEntry)) == Right(None), receiptIn(fixture(common, unsignedEntry)).toString)

  "a combination whose receipt states no rlp" should "make the file undecodable" in {
    // Present-and-unreadable is a broken fixture and absent is a fact about the
    // corpus. Folding the first into the second would drop the comparison
    // silently on exactly the file whose shape had changed.
    val withoutOctets = fixture(common, entryPublishing("""{ "status": true }"""))
    assert(receiptIn(withoutOctets).isLeft, receiptIn(withoutOctets).toString)
  }

  "published bytes beginning with zero" should "leave the format to the fields" in
    // Legacy's number is not a tag any proposal assigns, so these bytes are
    // malformed rather than legacy -- and reading them as legacy would silently
    // overrule an access list the case does state.
    assert(kindAt(envelope("0x00f8"), 0) == Some(TransactionType.AccessList), decoded(envelope("0x00f8")))

  "an index declaring an account with slots" should "carry both, in the order stated" in
    // The charge is per address and per key, so a declaration read short is a
    // transaction charged less than the chain charged it -- which surfaces as a
    // post-state root and never as a reader fault.
    assert(
      declaredAt(declaringAccessLists, 0) == Some(
        Vector(
          (
            "0000000000000000000000000000000000003000",
            Vector(
              "0000000000000000000000000000000000000000000000000000000000000001",
              "0000000000000000000000000000000000000000000000000000000000000002"
            )
          )
        )
      ),
      declaredAt(declaringAccessLists, 0).toString
    )

  "an index declaring an account and no storage keys" should "carry the account alone" in
    // The member is omitted rather than written empty, which is a shape the
    // published tiers use and a reader requiring it would refuse.
    assert(
      declaredAt(declaringAccessLists, 1) == Some(Vector(("0000000000000000000000000000000000004000", Vector.empty))),
      declaredAt(declaringAccessLists, 1).toString
    )

  "a declaration naming one account twice" should "keep the repeat rather than deduplicating it" in
    // EIP-2930 charges a repeat twice while the warm seed built from the same
    // field takes it once, so a reader that collapsed the two would undercharge
    // and leave the seed unchanged -- visible only as a root.
    assert(
      declaredAt(declaringAccessLists, 2).map(_.length) == Some(2),
      declaredAt(declaringAccessLists, 2).toString
    )

  "an index whose access list is null" should "declare nothing" in
    // Null is the index saying it predates the envelope. It is read as no
    // declaration for the same reason it is read as the earlier format.
    assert(declaredAt(mixedAccessLists, 0) == Some(Vector.empty), declaredAt(mixedAccessLists, 0).toString)

  "an index whose access list is empty" should "declare nothing" in
    // `[]` is a typed transaction listing nothing, which costs the same as
    // declaring nothing and is a different statement about the format. The
    // format case above is what separates them.
    assert(declaredAt(mixedAccessLists, 1) == Some(Vector.empty), declaredAt(mixedAccessLists, 1).toString)

  "a case stating no access lists at all" should "declare nothing" in {
    val plain = fixture(common, threeUnsignedEntries)
    assert(declaredAt(plain, 0) == Some(Vector.empty), declaredAt(plain, 0).toString)
  }

  "a case whose access lists do not reach a combination's index" should "make the file undecodable" in {
    // The field is parallel to `data`, so an index outside it is a file whose
    // two arrays disagree. Answering with an empty declaration would charge that
    // combination for less than it declares, which is the one outcome a reader
    // must not have.
    val short = fixture(common + ""","accessLists": [ [] ]""", threeUnsignedEntries)
    assert(StateFixture.decodeFile("classification", short).isLeft, decoded(short))
  }
