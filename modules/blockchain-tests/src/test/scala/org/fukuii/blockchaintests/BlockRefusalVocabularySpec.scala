package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.ExpectedRejection

/** Which of a refusal's stated names neither half of the vocabulary holds.
  *
  * Reported beside a divergence, so a case refused under a name this build has
  * never mapped can be told from a validator refusing for the wrong rule.
  * Whether a refusal satisfies its names is `BlockRefusalVocabularyPropSpec`'s
  * table.
  */
class BlockRefusalVocabularySpec extends AnyFlatSpec:

  /** A key no published corpus states. */
  private val Unpublished: String = "BlockException.NOT_A_PUBLISHED_NAME"

  "unmapped" should "report exactly the stated names neither half holds" in {
    val stated =
      ExpectedRejection(Set(Unpublished, "BlockException.INVALID_GASLIMIT", "TransactionException.NONCE_IS_MAX"))
    val unmapped = BlockRefusalVocabulary.unmapped(stated)
    assert(
      unmapped == Set(Unpublished),
      "a block name and a transaction name are both held, and only the unpublished key is not: " + unmapped.toString
    )
  }
