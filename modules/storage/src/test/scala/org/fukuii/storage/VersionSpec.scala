package org.fukuii.storage

import org.fukuii.bytes.Hash
import org.scalatest.flatspec.AnyFlatSpec

class VersionSpec extends AnyFlatSpec:

  private val hashA = Hash.fromHex("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470").toOption.get
  private val hashB = Hash.fromHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855").toOption.get

  "Version.apply" should "round-trip a block hash through .blockHash" in
    assert(Version(hashA).blockHash == hashA, "the block hash must survive the wrapper")

  "two Versions" should "compare equal for the same underlying block hash" in
    assert(Version(hashA) == Version(hashA), "equal block hashes must key the same snapshot")

  it should "compare unequal for different underlying block hashes" in
    assert(Version(hashA) != Version(hashB), "different block hashes must key distinct snapshots")
