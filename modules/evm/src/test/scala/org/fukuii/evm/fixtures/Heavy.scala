package org.fukuii.evm.fixtures

import org.scalatest.Tag

/** A test the ordinary suite does not run, because running it costs minutes.
  *
  * ==Opting IN is the decision, not opting out==
  *
  * `build.sbt` excludes this tag from every `Test` invocation, so a bare `test`
  * or `testFull` never pays for what carries it. A run that wants these asks for
  * them by name. **The default is the cheap suite and the expensive one is
  * requested**, which is the way round the field does it: `ethereum/execution-specs`
  * marks its long-running cases `@pytest.mark.slow` and gates real dataset
  * generation on an external binary, `ethereum/go-ethereum-pow` @ `v1.10.26`
  * exercises datasets at kilobyte scale in `algorithm_test.go` and puts real
  * generation behind a separate `makedag` target, and `besu-eth/besu-etc` builds a
  * real cache at a real epoch while verifying only the light path.
  *
  * ==What this costs, stated because it is a real loss and not a free win==
  *
  * A tagged test runs when somebody remembers, and this repository has no
  * continuous integration to remember for it. **So the coverage it provides is a
  * point-in-time reading rather than a standing one**, and a claim resting on it
  * ages from *verified* to *verified once, on the date it last ran*. That is
  * acceptable for a rule that does not change -- a proof-of-work mixing loop is
  * about as stable as protocol code gets -- and it is not acceptable for anything
  * under active edit. **Run the heavy suite while working in the code it covers**;
  * `AGENTS.md` § Commands carries the invocation and states when a section owes
  * one.
  *
  * ==The active-edit bar, and what may carry this tag despite it==
  *
  * The certification matrix's most expensive rows carry this tag while the fork
  * they read is under active edit, which the paragraph above appears to forbid.
  * They are admissible because of WHAT they assert, and the bar is not relaxed
  * to admit them: a matrix row asserts how many cases a published corpus would
  * answer differently were one proposal removed, which is a property of that
  * corpus rather than of the machine. **Whether the machine is right at that
  * fork is asserted by an untagged property that runs every case of the same
  * corpus on every ordinary run**, so a rule that regresses under edit still
  * fails a run nobody had to remember to ask for.
  *
  * **The residual is real, and it is what the section-close obligation covers.**
  * A change that left every published case still agreeing while moving what a
  * proposal's removal decides would not be seen until the next heavy run. That
  * is a narrower gap than the bar was written against, not an absent one.
  *
  * **So the test to apply is whether an ordinary run would still fail on a
  * regression in the rule**, rather than whether the rule is under edit. Where
  * it would not, the bar stands and the expensive thing does not get this tag.
  *
  * ==Why the tag lives in this module's test tree==
  *
  * It is not an EVM concern. It sits here because this is the tree four modules
  * take a `test->test` edge on, which makes it the only place every consumer can
  * already see -- the same reason the fixtures beside it live here. The tag STRING
  * is deliberately `org.fukuii.Heavy` rather than the package path, so the build's
  * exclusion argument names a project-wide concept rather than one module's.
  *
  * ==The count ratchet reads a different number under this tag==
  *
  * `scripts/test-expected-total.txt` holds the total of the DEFAULT suite, so
  * `scripts/check-test-run.sh` passes on an ordinary run. A heavy run executes
  * more than that file states and is checked against its own figure, which the
  * checker takes as its optional second argument. **Two totals, both explicit** --
  * the alternative is a gate that reports a partial run every time it is used
  * correctly, and a gate that cries wolf is one nobody reads.
  */
object Heavy extends Tag("org.fukuii.Heavy")
