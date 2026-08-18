package org.fukuii.storage

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Tabulated over every [[RetentionCategory]]. Holds no fixed examples of its
  * own; those live in [[RetentionVectorSpec]].
  */
class RetentionVectorPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val categories = Table("category", RetentionCategory.values.toIndexedSeq*)

  property("RetentionVector.Archive is Unbounded on every category") {
    forAll(categories) { category =>
      assert(RetentionVector.Archive(category) == RetentionBound.Unbounded, category.toString + " must be unbounded under archive")
    }
  }
