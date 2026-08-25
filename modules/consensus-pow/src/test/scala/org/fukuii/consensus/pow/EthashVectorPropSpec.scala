package org.fukuii.consensus.pow

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.io.Source
import scala.util.Using

import org.fukuii.bytes.Hash

/** Cache construction and FULL dataset construction, byte for byte, against a
  * reference client's own expected values.
  *
  * ==This is where the dataset is certified, and it is the only place it can be==
  *
  * The published `PoWTests` tier states a dataset SIZE and no dataset content,
  * so it cannot check the construction at all. `ethereum/go-ethereum-pow` @
  * `v1.10.26` states a whole expected cache and a whole expected dataset as
  * literals in `TestCacheGeneration` and `TestDatasetGeneration`, which is the
  * only byte-exact statement of either artifact anywhere in this project's
  * corpus. `scripts/gen-ethash-vectors.py` extracts them and regenerates every
  * one from the seed up before writing a row, so the table is a value two
  * implementations agree on rather than a transcription of one.
  *
  * ==Why a small dataset certifies the full construction and a large one would
  * not==
  *
  * An item is a pure function of the cache and its index, so the item count
  * changes no branch. What a real epoch's gigabyte would add is a longer loop
  * and **nothing to compare the result against** -- no published value states
  * one. The reference client made the same call: its own dataset test runs at
  * thirty-two kilobytes.
  *
  * ==The equality the last row checks is the one that matters for a real DAG==
  *
  * `hashimoto` over a built dataset and over a cache regenerating each item
  * must answer identically. That is the property a gigabyte-scale dataset can
  * be checked by when no expected value exists for it, and it is checked here
  * at a size the suite can afford.
  */
class EthashVectorPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val Resource: String = "/ethash-vectors.txt"

  private val rows: Vector[Vector[String]] =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream(Resource))) { source =>
      source
        .getLines()
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .map(_.split(' ').toVector)
        .toVector
    }

  private def rowsOf(kind: String): Vector[Vector[String]] = rows.filter(_.head == kind)

  private def bytesOf(hex: String): IArray[Byte] =
    org.fukuii.bytes.Bytes
      .fromHex(hex)
      .getOrElse(throw new IllegalStateException("a vector row is not hex"))
      .toIArray

  private def wordsOf(raw: IArray[Byte]): IArray[Int] =
    IArray.tabulate(raw.length / 4) { i =>
      (raw(i * 4) & 0xff) |
        ((raw(i * 4 + 1) & 0xff) << 8) |
        ((raw(i * 4 + 2) & 0xff) << 16) |
        ((raw(i * 4 + 3) & 0xff) << 24)
    }

  private def sameWords(left: IArray[Int], right: IArray[Int]): Boolean =
    left.length == right.length && left.indices.forall(i => left(i) == right(i))

  private val caches = Table(("epoch", "seed", "cache"), rowsOf("cache").map(r => (r(1), r(2), r(3)))*)
  private val datasets = Table(("epoch", "cacheSize", "dataset"), rowsOf("dataset").map(r => (r(1), r(2), r(3)))*)
  private val mixings =
    Table(
      ("cacheSize", "datasetSize", "sealHash", "nonce", "mixHash", "result"),
      rowsOf("hashimoto").map(r => (r(1), r(2), r(3), r(4), r(5), r(6)))*
    )

  property("the table was read at all") {
    assert(
      rowsOf("cache").length == 2 && rowsOf("dataset").length == 1 && rowsOf("hashimoto").length == 1,
      "a table read as empty makes every check below vacuous, so the shape is asserted before it is used: " +
        rows.length.toString + " rows"
    )
  }

  property("the seed a vector states is the one the epoch arithmetic answers") {
    forAll(caches) { (epoch, seed, _) =>
      assert(
        Ethash.seedFor(BigInt(epoch), Ethash.EpochLength).toBytes.toSeq == bytesOf(seed).toSeq,
        "epoch " + epoch + ": the vector's own seed disagrees, so the cache below would be built from the wrong one"
      )
    }
  }

  property("a cache is built byte for byte as the reference client states it") {
    forAll(caches) { (epoch, seed, expected) =>
      val raw = bytesOf(expected)
      val built =
        Ethash.cacheFrom(raw.length.toLong, Hash.fromBytesTruncating(bytesOf(seed)), BigInt(epoch), Ethash.EpochLength)
      assert(
        sameWords(built.words, wordsOf(raw)),
        "epoch " + epoch + ": the cache differs from go-ethereum-pow @ v1.10.26's TestCacheGeneration"
      )
    }
  }

  property("a full dataset is built byte for byte as the reference client states it") {
    forAll(datasets) { (epoch, cacheSize, expected) =>
      val raw = bytesOf(expected)
      val cacheRow = rowsOf("cache").find(_(1) == epoch).getOrElse(fail("no cache vector for epoch " + epoch))
      val cache = Ethash.cacheFrom(
        cacheSize.toLong,
        Hash.fromBytesTruncating(bytesOf(cacheRow(2))),
        BigInt(epoch),
        Ethash.EpochLength
      )
      assert(
        sameWords(Ethash.datasetFor(cache, raw.length.toLong).words, wordsOf(raw)),
        "epoch " + epoch + ": the dataset differs from go-ethereum-pow @ v1.10.26's TestDatasetGeneration"
      )
    }
  }

  property("the light path answers what the reference client states") {
    forAll(mixings) { (cacheSize, datasetSize, sealHash, nonce, mixHash, result) =>
      val cache = Ethash.cacheFrom(cacheSize.toLong, EthashFixtures.hashOf("00" * 32), BigInt(0), Ethash.EpochLength)
      val answered =
        Ethash.evaluateLight(cache, datasetSize.toLong, EthashFixtures.hashOf(sealHash), bytesOf(nonce))
      assert(
        answered == EthashSolution(EthashFixtures.hashOf(mixHash), EthashFixtures.hashOf(result)),
        "the cache path disagrees with go-ethereum-pow @ v1.10.26's TestHashimoto"
      )
    }
  }

  property("the full path answers exactly what the light path does") {
    forAll(mixings) { (cacheSize, datasetSize, sealHash, nonce, mixHash, result) =>
      val cache = Ethash.cacheFrom(cacheSize.toLong, EthashFixtures.hashOf("00" * 32), BigInt(0), Ethash.EpochLength)
      val dataset = Ethash.datasetFor(cache, datasetSize.toLong)
      assert(
        Ethash.evaluateFull(dataset, EthashFixtures.hashOf(sealHash), bytesOf(nonce)) ==
          EthashSolution(EthashFixtures.hashOf(mixHash), EthashFixtures.hashOf(result)),
        "the dataset path disagrees with the cache path, which is the property a real DAG is checked by"
      )
    }
  }
