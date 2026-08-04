// fukuii-cli — minimal build.
//
// This declares the stack entries that are settled and nothing else, so those
// choices are checkable against the build rather than asserted in prose.
//
// It deliberately declares NO library dependencies. There is no application
// code yet, and a dependency with no present need is not an entry. Each one is
// added when a real need arises, answering: what problem does this solve, why
// this over the alternatives, why this version, and what would change the
// answer.

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

lazy val root = (project in file("."))
  .settings(
    name := "fukuii-cli"
  )
