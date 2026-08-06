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

// The warning ratchet: every category below is an ERROR, from this commit.
//
// WHY NOW, AND WHY NOT LATER. A warning category costs nothing to gate before
// the code it would flag exists, and is a migration afterwards. There is one
// moment at which that cost is zero, it does not recur, and writing ungated
// code destroys it. This repository is at that moment: no src/main, and a
// handful of test sources. `.claude/protocols/warning-ratchet.md` holds the
// full warrant; this is its application.
//
// WHY AS ERRORS RATHER THAN WARNINGS TO BE WORKED DOWN. A warning nobody must
// fix is a warning nobody fixes. Enabling a category and leaving it advisory
// recreates, deliberately, the gap the ratchet exists to close.
//
// SCOPE IS ThisBuild, SO THIS BINDS `Test` TOO, DELIBERATELY. Test sources go
// through the same compiler as main sources, and test code is code: a category
// left ungated in Test accumulates exactly as it would anywhere else. Two clue
// strings were adjusted for -Wtostring-interpolated when this landed, which is
// what the ratchet costs while the tree is this small.
//
// ─────────────────── Do NOT collapse this list to -Wall ───────────────────
//
// -Wall exists at this compiler version and reads like the obvious shorthand.
// It is not: measured against a fixture carrying one violation per category,
// -Wall is a strict SUBSET. It misses -deprecation, -feature and -Xlint, and
// the first two are the most widely recommended flags in the language. A build
// saying -Wall would report a control that is not operating.
//
// -Wall plus those three was measured EQUIVALENT to the enumeration below, so
// the list is not stricter than -Wall+3 — it is legible, which -Wall is not.
// A reader can see what is enforced without running the compiler.
//
// Re-derive rather than trusting this paragraph, with
// `scripts/warning-ratchet-proof.sh`, whose fixture carries the cases.
//
// ───────────────── A vanished flag cannot fail silently ─────────────────
//
// An option the compiler does not recognize is reported as
// `bad option '-Xnope' was ignored` — a WARNING, which -Werror turns into an
// error. Measured on this compiler. So a flag renamed or withdrawn by a future
// Scala release breaks the build loudly at the bump, rather than being dropped
// while the build still reports success. -Werror is what closes that hole, and
// it is the reason a private -Y option is admissible below.
ThisBuild / scalacOptions ++= Seq(
  // Warn on deprecated API use, and on features requiring an explicit import
  // (implicit conversions, postfix operators, reflective calls). Neither is
  // covered by -Wall. `-unchecked` reports where an erased generic makes a
  // type test weaker than it appears.
  "-deprecation",
  "-feature",
  "-unchecked",

  // Unused imports, private members, locals, pattern variables and parameters.
  // `:all` rather than `:linted`, which omits locals and patvars.
  "-Wunused:all",

  // The two halves of "a non-Unit value was thrown away". They are distinct
  // categories with distinct diagnostics and one does not imply the other:
  // -Wvalue-discard is a non-Unit expression in Unit position, -Wnonunit-
  // statement is a non-Unit statement discarded mid-block.
  "-Wvalue-discard",
  "-Wnonunit-statement",

  // Interpolating a reference type relies on its toString. For a client whose
  // domain is hashes and byte arrays that is a live defect rather than a
  // stylistic one — an interpolated Array[Byte] renders as `[B@1a2b3c`. It
  // does NOT fire for Strings or primitives, so ordinary `s"n=$count"` clues
  // are unaffected; an explicit `.toString` records the decision where the
  // implicit conversion really is wanted.
  "-Wtostring-interpolated",

  // An inferred union type argument is almost always an unintended widening
  // rather than a design choice.
  "-Winfer-union",

  // Two narrow categories with no cost and a real failure behind each: a
  // scaladoc comment silently dropped because it sits above several enum
  // cases, and a recursive call that re-supplies a default argument, which is
  // the shape that fails to terminate.
  "-Wenum-comment-discard",
  "-Wrecurse-with-default",

  // A plain function literal where a context function was wanted. Enabled for
  // completeness; no case that triggers it as a WARNING could be constructed
  // at this compiler version — the shapes tried were type errors instead — so
  // unlike every other entry here it is unexercised by the proof's fixture.
  "-Wwrong-arrow",

  // A private field shadowing a superclass field, and a type parameter
  // shadowing one already in scope. Both silently change which binding is
  // read; neither is covered by -Wall.
  "-Xlint:all",

  // Initialization-order safety: a field read before it is assigned. Spelled
  // in the compiler's PRIVATE namespace at this version and promoted to
  // -Wsafe-init on later lines, so the name is transitional — which is
  // admissible only because of the -Werror property above, and is the trigger
  // to rename this entry when the compiler line moves.
  "-Ysafe-init",

  // The promotion itself. Everything above is advisory without it.
  "-Werror"
)

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
