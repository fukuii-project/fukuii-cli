package org.fukuii.chainspec

import org.fukuii.bytes.UInt64
import org.scalacheck.Gen
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** The comparison's properties over generated activations. Holds no fixed
  * examples; those live in [[ActivationSpec]].
  *
  * Points are generated from arbitrary bit patterns rather than from
  * non-negative values, so the half of the range above 2^63 -- where a signed
  * reading of the machine word inverts the answer -- is covered rather than
  * assumed away.
  */
class ActivationPropSpec extends AnyPropSpec with ScalaCheckPropertyChecks:

  private val order = Activation.activationOrdering

  private val anyPoint: Gen[UInt64] = Gen.choose(Long.MinValue, Long.MaxValue).map(UInt64.fromBits)

  private val anyActivation: Gen[Activation] =
    Gen.oneOf(
      anyPoint.map(Activation.AtBlock.apply),
      anyPoint.map(Activation.AtTimestamp.apply),
      Gen.const(Activation.Unscheduled),
      Gen.const(Activation.Never)
    )

  property("comparison is antisymmetric, including where it refuses") {
    forAll(anyActivation, anyActivation) { (left: Activation, right: Activation) =>
      assert(
        order.tryCompare(left, right).map(math.signum) == order.tryCompare(right, left).map(v => -math.signum(v)),
        "one direction answering while the other refuses would let an ordering check pass on the argument order"
      )
    }
  }

  property("two activations on one axis order by their points, unsigned") {
    forAll(anyPoint, anyPoint) { (left: UInt64, right: UInt64) =>
      assert(
        order
          .tryCompare(Activation.AtBlock(left), Activation.AtBlock(right))
          .map(math.signum)
          .contains(math.signum(java.lang.Long.compareUnsigned(left.toBits, right.toBits))),
        "the point is the protocol's machine word, and the comparison must read it as one"
      )
    }
  }

  property("a block activation precedes a timestamp activation at every pair of points") {
    forAll(anyPoint, anyPoint) { (blockNumber: UInt64, seconds: UInt64) =>
      assert(
        order.tryCompare(Activation.AtBlock(blockNumber), Activation.AtTimestamp(seconds)).contains(-1),
        "besu orders its whole schedule on the bare figure, which holds only while one network's timestamps exceed its heights"
      )
    }
  }
