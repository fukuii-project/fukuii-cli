// Fukuii — minimal build.
//
// This declares the stack entries that are settled and nothing else, so those
// choices are checkable against the build rather than asserted in prose.
//
// It declares no COMPILE-scope library dependencies. There is no application
// code yet, and a dependency with no present need is not an entry. Each one is
// added when a real need arises, answering: what problem does this solve, why
// this over the alternatives, why this version, and what would change the
// answer. The test-scope entries below are the first to answer those questions.

// Scala on the LTS line.
//
// This is line membership, not a version preference: the Scala Next line
// (3.8.x and later) is a different line this project does not run, rather than
// a higher number that was skipped. Stated as a numeric comparison the choice
// needs re-arguing every time the two lines' numbers interleave.
//
// The rule for every dependency here: current LTS; where no LTS exists, latest
// stable; never exploratory or head-of-branch releases. An LTS designation
// means maintainers have committed support for a defined period, which keeps
// this project's dependencies supported over a meaningful horizon and holds
// churn down.
ThisBuild / scalaVersion := "3.3.8"

ThisBuild / organization := "org.fukuii"

// Java 25 as the bytecode contract.
//
// This is half of the JDK pin, and not sufficient alone: -release fixes the API
// and bytecode level the compiler targets, not which JVM runs the build. It
// will catch a JDK older than the target; it cannot distinguish patch releases,
// and it reaches neither CI nor deployment. The other half is .sdkmanrc, which
// names the JDK and its distribution.
ThisBuild / scalacOptions ++= Seq("-release", "25")
ThisBuild / javacOptions ++= Seq("--release", "25")

// ScalaTest, named one style artifact at a time.
//
// The framework is not selected here. AGENTS.md "## Testing" already commits, in
// tracked public text, to a style-per-use-case policy written to ScalaTest's own
// taxonomy, and rejects five styles with recorded reasoning. This declares it so
// the policy is ENFORCED rather than asserted.
//
// NEVER the `scalatest` aggregate. The aggregate pulls all eight style artifacts
// plus the three matchers modules, which would silently re-admit both the five
// rejected styles and the matchers dialect AGENTS.md bans. Naming individual
// artifacts makes a policy violation a compile error instead of a review comment:
// with only the three below, `org.scalatest.wordspec` is "not a member of
// org.scalatest" — [E008], measured, exit 1.
//
// Three artifacts, mirroring AGENTS.md's assignment table 1:1:
//   flatspec     -> unit tests, and integration tests (same style, by policy)
//   propspec     -> property checks, and vector tables via TableDrivenPropertyChecks
//   featurespec  -> acceptance tests, with GivenWhenThen
//
// Declaring the ASSIGNED SET is implementing the policy, not changing it.
// AGENTS.md's "adding a style artifact is a policy change" governs adding a style
// BEYOND that set — its own next sentence, "do not add one to make a single file
// compile", is what it guards against. Declaring a subset would be worse than
// either: the build would then enforce a NARROWER policy than AGENTS.md publishes,
// a silent divergence between the standard and the mechanism meant to enforce it.
// This project has already shipped that bug once — pinning `scalatest-funsuite`
// alone made PropSpec impossible to compile.
//
// Two facts that are not guessable from the coordinates: TableDrivenPropertyChecks
// and GivenWhenThen both live in `scalatest-core`, which arrives transitively with
// any style artifact. So the matrix and acceptance use cases need no further entry.
//
// 3.2.20 is the newest STABLE release, built with Scala 3.1.3 — inside our LTS
// line. Gate 0 confirmed per artifact on two agreeing instruments: the published
// POM's declared `scala3-library_3`, and the TASTy header inside the shipped
// bytecode, which no build-time override or version eviction can defeat.
//
// DO NOT read the version off Maven Central's `<release>`/`<latest>` metadata.
// Both report `3.3.0-SNAP4` — a prerelease dated 2023, three years OLDER than
// 3.2.20. Maven excludes only the literal `-SNAPSHOT`; `SNAP4` sorts above
// `alpha`, and 3.3.0 > 3.2.20 numerically. Read the full `<versions>` list.
lazy val scalatestVersion = "3.2.20"

lazy val root = (project in file("."))
  .settings(
    // The PROJECT is fukuii. `fukuii-cli` is the repository's name, not the
    // project's, and this setting is the artifact coordinate — it publishes as
    // org.fukuii:fukuii_3, so the repo name must not leak into it. Lowercase
    // because this is a Maven artifactId, not a display name.
    name := "fukuii",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest-flatspec"    % scalatestVersion % Test,
      "org.scalatest" %% "scalatest-propspec"    % scalatestVersion % Test,
      "org.scalatest" %% "scalatest-featurespec" % scalatestVersion % Test
    )
  )
