package org.fukuii

import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.GivenWhenThen

/** Proves the `scalatest-featurespec` artifact resolves, that `GivenWhenThen`
  * reaches it from `scalatest-core` transitively, and that AGENTS.md's own
  * documented syntax actually compiles against the pinned ScalaTest.
  *
  * That last point closes a gap AGENTS.md flags against itself. It requires the
  * capitalised `Feature`/`Scenario` — the lowercase forms were deprecated in
  * ScalaTest 3.1.0 — but says the "still compiles" half could not be checked
  * because no ScalaTest version was pinned at the time. It is pinned now, and
  * this file is written in exactly the form AGENTS.md documents, so a change in
  * that syntax becomes a build failure rather than a stale doc.
  *
  * The shared warrant, and the retirement trigger, are in [[ToolchainFlatSpec]].
  */
class ToolchainFeatureSpec extends AnyFeatureSpec with GivenWhenThen:

  Feature("the featurespec artifact resolves and runs"):
    Scenario("a scenario states its steps in domain language"):
      Given("three style artifacts declared in build.sbt")
      val declared = Set("flatspec", "propspec", "featurespec")

      When("the acceptance style is exercised")
      val rejected = Set("funsuite", "funspec", "wordspec", "freespec", "refspec")

      Then("the declared set and the rejected set are disjoint")
      assert(
        declared.intersect(rejected).isEmpty,
        s"declared and rejected styles overlap: ${declared.intersect(rejected)}"
      )
