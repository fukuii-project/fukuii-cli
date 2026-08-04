package org.fukuii

import org.scalatest.flatspec.AnyFlatSpec

/** Proves the `scalatest-flatspec` artifact resolves and that the toolchain
  * reports a real result.
  *
  * ==Why these specs exist==
  *
  * A framework that has never reported a failure is indistinguishable from one
  * that CANNOT report a failure. Section 01 shipped three instruments that could
  * not fail before the pattern was caught; the test toolchain is not going to be
  * a fourth. Each assertion here is negated once, by hand, and observed to FAIL
  * before the section closes.
  *
  * ==Why one spec per artifact==
  *
  * `build.sbt` names the three style artifacts individually rather than taking
  * the `scalatest` aggregate, so that a style AGENTS.md rejects fails to resolve.
  * "Does this artifact actually resolve and run" is therefore a per-artifact
  * question, and one spec cannot answer it for three artifacts. See
  * [[ToolchainPropSpec]] and [[ToolchainFeatureSpec]], which carry the same
  * warrant for the other two.
  *
  * ==Retirement==
  *
  * RETIRE WHEN: `modules/bytes` has its first real `AnyFlatSpec`. These prove the
  * toolchain, not the code; a real spec proves both, and then these are an
  * artifact with nothing to do. Delete all three together.
  */
class ToolchainFlatSpec extends AnyFlatSpec:

  "the flatspec artifact" should "resolve and evaluate an assertion" in {
    val squares = (1 to 4).map(n => n * n)
    assert(squares == Seq(1, 4, 9, 16), s"squares of 1..4, got $squares")
  }

  it should "continue a subject with `it`, which is the reason for choosing FlatSpec" in {
    val subject = "the flatspec artifact"
    assert(subject.nonEmpty, "a FlatSpec subject must be a non-empty string")
  }
