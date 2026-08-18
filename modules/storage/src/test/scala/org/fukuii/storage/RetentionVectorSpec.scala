package org.fukuii.storage

import org.scalatest.flatspec.AnyFlatSpec

class RetentionVectorSpec extends AnyFlatSpec:

  "RetentionVector.apply" should "return the declared bound for a category present in the vector" in {
    val vector = RetentionVector(Map(RetentionCategory.StateHistory -> RetentionBound.Depth(90000)))
    assert(vector(RetentionCategory.StateHistory) == RetentionBound.Depth(90000), "a present category returns its own bound")
  }

  it should "default an unspecified category to Unbounded" in {
    val vector = RetentionVector(Map.empty)
    assert(vector(RetentionCategory.Commitment) == RetentionBound.Unbounded, "absence means no bound stated, not retain nothing")
  }

  "RetentionVector" should "admit a category bounded by a finality watermark rather than a depth" in {
    val vector = RetentionVector(Map(RetentionCategory.StateHistory -> RetentionBound.FinalityWatermark))
    assert(vector(RetentionCategory.StateHistory) == RetentionBound.FinalityWatermark, "a watermark bound is a distinct shape from a depth")
  }
