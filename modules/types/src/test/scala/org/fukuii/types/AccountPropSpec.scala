package org.fukuii.types

import org.fukuii.bytes.{Hash, Hex, UInt256, UInt64}
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Accounts against octets produced by the executable specification.
  *
  * ==Why this source, and how it differs from the block header's==
  *
  * A header could be certified against octets that shipped inside a real block.
  * An account cannot: its natural certification is through the state trie root,
  * and the trie is a later section, so no fixture publishes an account's
  * encoding directly.
  *
  * What is available is stronger than a client and weaker than shipped bytes:
  * the specification is executable, so **its own `encode_account` produced
  * every expectation here** rather than this project's reading of a formula.
  * The resource names the repository.
  *
  * The field *values* have two provenances and the label says which. Rows
  * labelled `corpus-` carry real published accounts lifted from fixture
  * pre-state, each located in the corpora the resource names. Rows labelled
  * `authored-` are constructed here and published nowhere: the table walks
  * nonce and balance byte-widths, and no corpus supplies an account at some of
  * them — a chain account does not carry nonce 2^64-1. Only accounts with empty
  * storage are taken, because any other storage root needs the trie. The
  * empty-trie root and the empty-code hash are derived from first principles at
  * generation — keccak of RLP of the empty string, and keccak of the empty
  * string — never recalled.
  *
  * Keeping the two apart is the point rather than bookkeeping: six rows once
  * carried constructed values under a `corpus-` label, and nothing in a passing
  * suite could show it, because a vector that is wrong about where it came from
  * still encodes correctly.
  *
  * ==What the table is selected for==
  *
  * Encoding shape, not volume. Rows cover the distinct combinations of
  * nonce byte-width, balance byte-width, and whether the account has code —
  * which is what varies the octets. Sampling 535,051 pre-state accounts by
  * position instead would have certified the zero-balance shape dozens of times.
  */
class AccountPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(label: String, account: Account, rlp: String)

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(
      label = c(0),
      account = Account(
        nonce = UInt64.fromBigInt(BigInt(c(1))).toOption.get,
        balance = UInt256.fromBigInt(BigInt(c(2))).toOption.get,
        storageRoot = Hash.fromHex(c(3)).toOption.get,
        codeHash = Hash.fromHex(c(4)).toOption.get
      ),
      rlp = c(5)
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/account-vectors.txt"))
      .getOrElse(throw new IllegalStateException("account-vectors.txt is not on the test classpath"))
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val accounts = Table(("vector"), vectors*)

  property("the table covers both account kinds and the scalar extremes") {
    val labels = vectors.map(_.label).toSet
    assert(
      labels.contains("empty-account") && labels.contains("max-nonce-max-balance")
        && labels.exists(_.endsWith("contract")) && labels.exists(_.endsWith("externally-owned")),
      "an empty account, both extremes, and both kinds"
    )
  }

  property("an account encodes to the octets the specification produced") {
    forAll(accounts) { (v: Vector) =>
      assert(Hex.encode(RlpCodec.encodeTo(v.account)) == v.rlp, s"${v.label}: must match the specification")
    }
  }

  property("the specification's octets decode back to the same account") {
    forAll(accounts) { (v: Vector) =>
      val bytes = Hex.decode(v.rlp).toOption.get
      assert(RlpCodec.decodeFrom[Account](bytes) == Right(v.account), s"${v.label}: round trip must be exact")
    }
  }

  /** The two roots are adjacent and the same type, so a transposition produces
    * a well-formed list. Nothing but a row where they differ can catch it.
    */
  private val rootsDiffer = vectors.filter(v => v.account.storageRoot != v.account.codeHash)

  property("the table contains rows where the two roots differ, or the check below is vacuous") {
    assert(rootsDiffer.nonEmpty, "a transposition is invisible on a row whose roots are equal")
  }

  property("transposing the storage root and the code hash changes the encoding") {
    forAll(Table(("vector"), rootsDiffer*)) { (v: Vector) =>
      val swapped = v.account.copy(storageRoot = v.account.codeHash, codeHash = v.account.storageRoot)
      assert(
        Hex.encode(RlpCodec.encodeTo(swapped)) != v.rlp,
        s"${v.label}: storage and code identities are not interchangeable"
      )
    }
  }
