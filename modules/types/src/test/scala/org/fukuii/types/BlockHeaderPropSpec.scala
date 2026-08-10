package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, Hex, UInt256, UInt64}
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Block headers against the octets a client actually produced, and against the
  * block hash it published for them.
  *
  * Each row is element 0 of a real block's RLP, taken beside that same
  * fixture's decoded header fields and its expected hash. The extraction
  * re-encoded every row from those decoded fields using an implementation
  * independent of this one, and checked keccak of the result against the
  * published hash, before writing the row — so a mis-sliced block fails to
  * extract rather than becoming a wrong expectation that this suite then agrees
  * with.
  *
  * **The resource itself carries the immutable ref of every source**, and is
  * the authority for provenance rather than this paragraph. Two facts from it
  * bear on how far the evidence reaches: the fixture directories are **two
  * upstream repositories, not three** — two of them are one repository at two
  * commits — and the newer of the two is dormant at a ref that is
  * simultaneously its latest tag and its branch head, so no fork after Prague
  * is represented.
  *
  * ==Three obligations, and the third is the consensus-visible one==
  *
  * Fields encode to the published octets; the published octets decode to the
  * fields; and `keccak(rlp(header))` is the published block hash. A field in
  * the wrong order satisfies neither of the first two and changes the third on
  * every block — which is a fork rather than a defect, and is why the encoding
  * is pinned against bytes rather than against this project's reading of a
  * specification.
  *
  * ==What the table does and does not cover==
  *
  * Every field count the corpora contain — 15, 16, 20 and 21 — across every
  * fork family, including all five proof-of-work forks this client targets.
  *
  * It cannot cover a non-zero nonce: the corpora seal with a no-proof engine,
  * so all 243,474 of their headers carry a zero one, and none has a difficulty
  * above five figures. The `mainnet-genesis` row is there for exactly that gap
  * and is the one row assembled rather than sliced — admitted only because it
  * reproduces the genesis hash two independent clients publish as a constant.
  *
  * A tail longer than this type models is likewise absent from the corpora, so
  * it is covered by construction in [[BlockHeaderSpec]] and labelled there.
  */
class BlockHeaderPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** One row of the resource: the network label, the decoded fields, the header
    * encoding, and the block hash.
    */
  private case class Vector(network: String, header: BlockHeader, rlp: String, hash: String)

  private def bytesOf(cell: String): IArray[Byte] =
    if cell == "-" then IArray.empty else Hex.decode(cell).toOption.get

  private def hashOf(cell: String): Hash          = Hash.fromHex(cell).toOption.get
  private def wordOf(cell: String): UInt256       = UInt256.fromBytes(bytesOf(cell)).toOption.get
  private def machineWordOf(cell: String): UInt64 = UInt64.fromBytes(bytesOf(cell)).toOption.get

  /** Builds the tail by an explicit case per field count.
    *
    * Deliberately NOT by walking the chain the way the decoder does: a fixture
    * assembled by the logic under test agrees with that logic's own bugs, which
    * is the failure this whole suite is arranged to avoid.
    */
  private def tailOf(n: Int, f: IndexedSeq[String]): Option[BaseFeeTail] =
    def baseFee   = wordOf(f(15))
    def withdraw  = hashOf(f(16))
    def blobUsed  = machineWordOf(f(17))
    def blobExtra = machineWordOf(f(18))
    def beacon    = hashOf(f(19))
    def requests  = hashOf(f(20))
    n match
      case 15 => None
      case 16 => Some(BaseFeeTail(baseFee))
      case 17 => Some(BaseFeeTail(baseFee, Some(WithdrawalsTail(withdraw))))
      case 19 =>
        Some(BaseFeeTail(baseFee, Some(WithdrawalsTail(withdraw, Some(BlobGasTail(blobUsed, blobExtra))))))
      case 20 =>
        Some(
          BaseFeeTail(
            baseFee,
            Some(WithdrawalsTail(withdraw, Some(BlobGasTail(blobUsed, blobExtra, Some(BeaconRootTail(beacon))))))
          )
        )
      case 21 =>
        Some(
          BaseFeeTail(
            baseFee,
            Some(
              WithdrawalsTail(
                withdraw,
                Some(BlobGasTail(blobUsed, blobExtra, Some(BeaconRootTail(beacon, Some(RequestsTail(requests))))))
              )
            )
          )
        )
      case other => throw new IllegalArgumentException(s"no header shape has $other fields")

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    val n = c(1).toInt
    val f = c.drop(2)
    Vector(
      network = c(0),
      header = BlockHeader(
        parentHash = hashOf(f(0)),
        ommersHash = hashOf(f(1)),
        beneficiary = Address.fromHex(f(2)).toOption.get,
        stateRoot = hashOf(f(3)),
        transactionsRoot = hashOf(f(4)),
        receiptsRoot = hashOf(f(5)),
        logsBloom = Bloom.fromHex(f(6)).toOption.get,
        difficulty = wordOf(f(7)),
        number = machineWordOf(f(8)),
        gasLimit = machineWordOf(f(9)),
        gasUsed = machineWordOf(f(10)),
        timestamp = machineWordOf(f(11)),
        extraData = if f(12) == "-" then Bytes.Empty else Bytes.fromHex(f(12)).toOption.get,
        mixHash = hashOf(f(13)),
        nonce = BlockNonce.fromHex(f(14)).toOption.get,
        tail = tailOf(n, f)
      ),
      rlp = f(n),
      hash = f(n + 1)
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/block-header-vectors.txt"))
      .getOrElse(throw new IllegalStateException("block-header-vectors.txt is not on the test classpath"))
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val headers = Table(("vector"), vectors*)

  property("the resource carries every field count the corpora contain") {
    assert(vectors.map(_.header.fieldCount).toSet == Set(15, 16, 20, 21), "15/16/20/21, and nothing else")
  }

  property("a header encodes to the octets that shipped in the block") {
    forAll(headers) { (v: Vector) =>
      assert(Hex.encode(RlpCodec.encodeTo(v.header)) == v.rlp, s"${v.network}: must match the block's own bytes")
    }
  }

  property("the block's own bytes decode back to the same header") {
    forAll(headers) { (v: Vector) =>
      val bytes = Hex.decode(v.rlp).toOption.get
      assert(RlpCodec.decodeFrom[BlockHeader](bytes) == Right(v.header), s"${v.network}: round trip must be exact")
    }
  }

  property("keccak of the header's encoding is the published block hash") {
    forAll(headers) { (v: Vector) =>
      assert(v.header.hash.toHex == v.hash, s"${v.network}: the block hash is the consensus-visible value")
    }
  }

  /** The one row with a non-zero nonce, checked as itself rather than only as a
    * member of the table — the corpora cannot supply this and a reader should
    * not have to take the coverage note on trust.
    */
  property("the assembled genesis row carries a non-zero nonce and is still exact") {
    val genesis = vectors.find(_.network == "mainnet-genesis").get
    assert(
      genesis.header.nonce.toHex == "0000000000000042" && genesis.header.hash.toHex == genesis.hash,
      "a nonce with leading zeros survives, and the hash still lands"
    )
  }
