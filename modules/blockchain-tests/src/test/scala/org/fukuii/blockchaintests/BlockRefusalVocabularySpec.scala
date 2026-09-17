package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.ExpectedRejection

/** Which of a refusal's stated names no half of a tool's vocabulary holds.
  *
  * Reported beside a divergence, so a case refused under a name this build has
  * never mapped can be told from a validator refusing for the wrong rule.
  * Whether a refusal satisfies its names is `BlockRefusalVocabularyPropSpec`'s
  * table.
  */
class BlockRefusalVocabularySpec extends AnyFlatSpec:

  /** A key no published corpus states. */
  private val Unpublished: String = "BlockException.NOT_A_PUBLISHED_NAME"

  "unmapped" should "report exactly the stated names no half of the generated tier's vocabulary holds" in {
    // A block name, a transaction name and a decode name are each held; the
    // unpublished key is not.
    val stated =
      ExpectedRejection(
        Set(
          Unpublished,
          "BlockException.INVALID_GASLIMIT",
          "TransactionException.NONCE_IS_MAX",
          "BlockException.RLP_STRUCTURES_ENCODING"
        )
      )
    val unmapped = BlockRefusalVocabulary.unmapped(stated, FillingTool.ExecutionSpecs)
    assert(unmapped == Set(Unpublished), unmapped.toString)
  }

  it should "report a name another tool writes as unmapped in a legacy corpus" in {
    // testeth writes the first two and retesteth the last two, so a corpus
    // testeth filled holds the first two alone.
    val stated = ExpectedRejection(Set("TooMuchGasUsed", "InvalidTimestamp", "InvalidGasLimit2", "UnknownParent2"))
    val unmapped = BlockRefusalVocabulary.unmapped(stated, FillingTool.Testeth)
    assert(unmapped == Set("InvalidGasLimit2", "UnknownParent2"), unmapped.toString)
  }
