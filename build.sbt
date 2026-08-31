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

// The ordinary suite does not run what costs minutes.
//
// A test carrying `org.fukuii.Heavy` is excluded from every `Test` invocation
// here, so `test` and `testFull` stay cheap and a run that wants the expensive
// coverage asks for it. That direction is the field's: execution-specs marks its
// long cases `@pytest.mark.slow`, go-ethereum-pow exercises datasets at kilobyte
// scale and puts real generation behind `makedag`, and besu-etc builds a real
// cache while verifying only the light path. None of them pays a multi-minute
// cost on an ordinary run.
//
// The cost of the choice is stated where the tag is defined: nothing runs the
// heavy suite on a schedule, because this repository has no CI, so its coverage
// is point-in-time rather than standing.
//
// `scripts/test-expected-total.txt` therefore holds the DEFAULT total. A heavy
// run executes more and is checked against its own figure, which
// `scripts/check-test-run.sh` takes as an optional second argument.
ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "org.fukuii.Heavy")

// Java 25 as the bytecode contract.
//
// This is half of the JDK pin, and not sufficient alone: -release fixes the API
// and bytecode level the compiler targets, not which JVM runs the build. It
// will catch a JDK older than the target; it cannot distinguish patch releases,
// and it reaches neither CI nor deployment. The other half is .sdkmanrc, which
// names the JDK and its distribution.
//
// The -release pair is set inside the single scalacOptions assignment below
// rather than in its own statement, for the reason that assignment documents.
ThisBuild / javacOptions := Seq("--release", "25")

// How long the build server outlives the command that started it.
//
// sbt's own default is SEVEN DAYS -- `serverIdleTimeout := Some(new
// FiniteDuration(7, TimeUnit.DAYS))` at `main/src/main/scala/sbt/Defaults.scala`
// in `sbt/sbt` at `v2.0.4`, the version `project/build.properties` pins. The key
// itself documents the effect: "sbt server will exit if it goes at least the
// specified duration without receiving any commands."
//
// That default is wrong for a shared workstation. `.jvmopts` caps the heap at
// 4g, and a server left from a finished session was measured holding 3.0 GB --
// roughly a tenth of this machine's memory -- fourteen hours after its last
// command. Nothing reports that: an idle server contributes nothing to load
// average, so the pre-task check this project's house rules prescribe cannot
// see it.
//
// Thirty minutes keeps the server warm across a working session, which is worth
// real time -- a clean full run reuses it and finishes in seconds -- and reaps
// it once the session is over. sbt reaps a SECONDARY server after ten minutes
// by its own default (`sbt.server.secondaryIdleTimeout`, 600s), so this is the
// same order of magnitude rather than an unusual value.
ThisBuild / serverIdleTimeout := Some(scala.concurrent.duration.Duration(30, java.util.concurrent.TimeUnit.MINUTES))

// The warning ratchet: every category below is an ERROR, from this commit.
//
// WHY NOW, AND WHY NOT LATER. A warning category costs nothing to gate before
// the code it would flag exists, and is a migration afterwards. There is one
// moment at which that cost is zero, it does not recur, and writing ungated
// code destroys it. This repository took that moment: the ratchet was
// configured while it held no application code, so the foundation layer was
// written under it rather than migrated onto it, and every category below has
// been paid for exactly once. `.claude/protocols/warning-ratchet.md` holds the
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
// ─────────── ASSIGNED with :=, never appended with ++=, and why ───────────
//
// `ThisBuild / scalacOptions ++= …` accumulates ONCE PER PROJECT. With a single
// project that is invisible; the moment a second one exists the list is applied
// twice, and every flag in it becomes a `set repeatedly` error under -Werror.
//
// Measured here at the commit that introduced the module tree: four projects
// produced 64 entries — this 16-entry list, four times over — and the build
// failed on flag duplication rather than on anything in the code.
//
// So this is ONE assignment holding everything, including the -release pair.
// A second `ThisBuild / scalacOptions` statement of any kind reintroduces the
// defect; add a flag to this list instead.
ThisBuild / scalacOptions := Seq(
  // The bytecode contract. See the -release note above.
  "-release",
  "25",

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

// ScalaCheck and the ScalaTest bridge, Test scope.
//
// AGENTS.md "## Testing" assigns AnyPropSpec to property checks and forbids
// fixed examples there — but generator-driven `forAll` comes from ScalaCheck
// through the bridge, and neither was declared, so the assigned use case could
// not actually be written. `TableDrivenPropertyChecks` arrives in
// scalatest-core and supplies only the TABLE form, which is the other half of
// that assignment, not this one.
//
// The bridge's artifact name encodes the paired library's major.minor
// (`scalacheck-1-19`), so a ScalaCheck major bump re-pairs it — that is the
// reopen trigger, not a version bump on either side alone.
//
// Gate 0 binds these at Test scope exactly as at compile scope, and was
// re-confirmed at these versions on two agreeing instruments each (the shipped
// TASTy header and the published POM's declared scala3-library_3): ScalaCheck
// 1.19.0 built with Scala 3.3.6, the bridge 3.2.20.0 with 3.3.7. Both inside
// our LTS line.
//
// The bridge's transitive closure is already present by way of the three style
// artifacts above — it resolves scalatest-core_3, which scalatest-flatspec_3
// already pulls — so this adds no admissibility surface beyond what the build
// already carried.
lazy val scalacheckVersion = "1.19.0"
lazy val scalacheckBridgeVersion = "3.2.20.0"

// BouncyCastle: L0's cryptography provider, and the build's FIRST compile-scope
// dependency.
//
// Need-first, and the need is specific: the EVM digest (Keccak with the legacy
// 0x01 padding, which is NOT the JDK's SHA-3 — different padding, different
// output for the same input), secp256k1 ECDSA with RFC-6979 deterministic
// nonces and low-S canonicalization, secp256r1, and a constant-time comparison
// primitive. The JDK supplies none of the first three.
//
// It does NOT supply BLAKE2F, and this list said it did until the fork that
// needed it was built. `Blake2bDigest` is a streaming digest only: `compress`
// is private and its round count is a hardcoded field (`bcgit/bc-java` @
// `r1rv85`, Blake2bDigest.java:488 and :75), so the public surface —
// `update`/`doFinal`/`reset` — can be asked for a hash and cannot be asked for
// one application of `F` at a caller-supplied round count over a
// caller-supplied state, which is what EIP-152's precompile needs. `F` is
// hand-rolled instead, in
// `modules/crypto/src/main/scala/org/fukuii/crypto/Blake2b.scala`, where it is
// still cross-checked against this provider's own digest at the fixed round
// count that provider can reach. The claim is recorded rather than deleted so
// the dead end is not re-investigated.
//
// 1.85 is separately a security release; that selects the VERSION, not the
// dependency. Its published POM declares NO dependencies at all, so it arrives
// alone. Gate 0 does not bind it — it is a Java artifact, confirmed by the
// absence of any TASTy entry in the shipped jar rather than inferred from the
// coordinate's missing _3 suffix.
//
// 1.85.2 exists and is deliberately not taken: it is inside the release-age
// cooldown, and a commit-by-commit read of the interval found nothing touching
// keccak, secp256k1 or secp256r1. Revisit on ordinary currency.
lazy val bouncyCastleVersion = "1.85"

// circe -- JSON, TEST SCOPE ONLY, and only where a test actually reads JSON.
//
// WHY AT ALL. The EVM is certified against externally published fixture corpora
// -- ethereum/execution-specs-fixtures and ethereum/legacytests -- and every one
// of them is JSON. Nothing in this repository could read JSON, so there was no
// path to certification that did not either take a dependency or hand-roll a
// parser. The hand-rolled option was assessed and declined: a scanner that
// mishandles an escaped quote mid-string does not necessarily fail, it can
// resync and produce a well-formed-looking tree with silently wrong values --
// and a certification harness that misparses reports conformance it never
// measured, which is worse than having no harness.
//
// GATE 0 PASSES ACROSS THE WHOLE CLOSURE, not just the named coordinates. All
// eight artifacts circe drags in -- circe-{core,parser,jawn,numbers}, cats-core,
// cats-kernel, jawn-parser, scalac-compat-annotation -- declare a
// scala3-library_3 at 3.3.8 or below, inside our LTS line. Checked on two
// instruments per artifact: the published POM's declared scala3-library_3, and
// the TASTy header read out of the shipped bytecode. The coordinate cannot
// answer this on its own -- every Scala 3 artifact carries the same _3 suffix.
//
// 0.14.16 IS THE NEWEST STABLE, AND THE METADATA WILL TELL YOU OTHERWISE.
// Maven's own <latest> and <release> fields both report 0.15.0-M1, a milestone.
// Anything that takes "release" literally pulls a prerelease. Published
// 2026-06-24, so it is long past the release-age cooldown.
//
// AND IT CARRIES A FIX WE WANT RATHER THAN ONE WE TOLERATE. The transitive
// jawn-parser resolves to 1.7.0, which is the patched version for CVE-2026-59990
// (uncontrolled JSON nesting depth) and CVE-2026-61814 (quadratic parsing) --
// both HIGH, both affecting jawn-parser 1.6.0 and below. The fixtures are deeply
// nested JSON, so this is on-topic rather than incidental. Note that OSV and NVD
// carry neither advisory under any lookup key as of 2026-08-19, so a scanner run
// against a build on an older jawn reports clean; the version is right here by
// resolution rather than by anything a scanner would have told us.
//
// The parser is used at its defaults, deliberately, and one default is worth
// naming because it is invisible downstream. `parse` builds a `JsonObject`, so
// a duplicated key collapses AT PARSE TIME on a last-wins basis -- the fixture
// loaders detect duplicates while folding, but they fold over what circe
// already produced, so a collapse is not something they can see. Accepted
// rather than guarded: the corpora are machine-generated, and last-wins is what
// every reference client's own parser does with the same input, so a guard here
// would make fukuii reject a file the field accepts. Revisit only if a
// hand-authored fixture ever enters the corpus.
// Licenses: circe Apache-2.0, matching this project; cats and jawn MIT.
lazy val circeVersion = "0.14.16"

// Test dependencies are identical in every module, so they are defined once.
// A per-module copy is how one module silently ends up on a different test
// stack than its siblings.
lazy val testDeps = Seq(
  "org.scalatest" %% "scalatest-flatspec" % scalatestVersion % Test,
  "org.scalatest" %% "scalatest-propspec" % scalatestVersion % Test,
  "org.scalatest" %% "scalatest-featurespec" % scalatestVersion % Test,
  "org.scalacheck" %% "scalacheck" % scalacheckVersion % Test,
  "org.scalatestplus" %% "scalacheck-1-19" % scalacheckBridgeVersion % Test
)

// ───────────────────────────── The module tree ─────────────────────────────
//
// L0 is the foundation layer: byte-shaped value types, the RLP codec, and the
// cryptographic primitives. The dependency edges below are the real ones —
// `rlp` and `crypto` each build on `bytes` and neither depends on the other,
// so they are siblings rather than a chain. Encoding that in the build is what
// makes a cycle between them a build error instead of a review comment.

// bytes — the value types every layer above L0 uses as its currency.
//
// No compile-scope dependency, and that is a decision rather than an accident.
// The value types are a final class over IArray[Byte] with explicit equals and
// hashCode: IArray gives compile-time immutability while erasing to
// Array[Byte], so a value is still one object, and the class supplies the
// equality an opaque type cannot. An opaque type over an array was measured
// failing equality, Map lookup and Set dedup — it erases, so it inherits the
// array's identity semantics. That is why no general-purpose byte-container
// library is declared here: the shape that works needs no library.
lazy val bytes = (project in file("modules/bytes"))
  .settings(
    name := "fukuii-bytes",
    libraryDependencies ++= testDeps
  )

// rlp — the Recursive Length Prefix codec.
lazy val rlp = (project in file("modules/rlp"))
  .dependsOn(bytes)
  .settings(
    name := "fukuii-rlp",
    libraryDependencies ++= testDeps
  )

// crypto — the digest, curve and constant-time primitives.
//
// Declared here with no sources yet: the project and its dependency edge are
// part of the build shape, which lands once, while the module is populated in
// its own phase. sbt compiles zero sources without complaint.
lazy val crypto = (project in file("modules/crypto"))
  .dependsOn(bytes)
  .settings(
    name := "fukuii-crypto",
    libraryDependencies ++= testDeps,
    libraryDependencies += "org.bouncycastle" % "bcprov-jdk18on" % bouncyCastleVersion
  )

// types — the domain values every layer above L1 speaks, and their canonical
// encodings.
//
// Named for what the field names it. Four of the six reference clients call
// this package `types`; the two that say `core` use that name for the
// execution engine WITH these types inside it, which is a scope this module
// deliberately does not have. `domain` appears nowhere in the corpus.
//
// It depends on rlp (which brings bytes transitively, declared here anyway
// because these types name Address and Hash directly), and on crypto: a block
// header's hash IS keccak over its own RLP encoding, so the digest is not an
// accessory to this layer but the thing a header exists to produce. The edge
// was deliberately left out until a type here needed it rather than declared
// against a type that might.
lazy val types = (project in file("modules/types"))
  .dependsOn(bytes, rlp, crypto)
  .settings(
    name := "fukuii-types",
    libraryDependencies ++= testDeps
  )

// storage — the byte-pure key/value persistence contract: namespace
// separation along L2's two axes, the atomic batch, the versioned view, and
// one in-memory implementation.
//
// Depends on bytes alone, deliberately not on types: every key and value
// here is Bytes, and the layer that constructs Namespace values decides what
// the bytes mean. A dependency on types would make Account or BlockHeader
// reachable from this module, which is precisely what byte-purity forbids.
lazy val storage = (project in file("modules/storage"))
  .dependsOn(bytes)
  .settings(
    name := "fukuii-storage",
    libraryDependencies ++= testDeps
  )

// trie — L2's other half: the Merkle-Patricia node model, its encoding, its
// hashing, and the seam a state root is computed over.
//
// The edge set was left unsettled when `storage` landed, because a module's
// edges are a claim about what it needs and an empty project would have
// committed to an incomplete one. It is settled here, and every edge is used
// by a type in this module rather than declared against one that might be:
//
//   bytes    keys, values and digests are Bytes, Hash and Address
//   rlp      a node IS its RLP encoding; the commitment is taken over those
//            bytes, so the codec is not an accessory to this layer
//   crypto   the node cap rule and the root rule are both keccak-256, and a
//            secured trie hashes every key before insertion
//   storage  the versioned key/value view a commitment is computed over, and
//            the content-addressed node keyspace the other implementation
//            writes into
//   types    the state trie's leaf value is an RLP-encoded account. The
//            alternative was a second account encoding written here, which is
//            the one hazard RlpCodec's contract exists to forbid: one value
//            with two encodings, selected by which layer a call site is in
//
// The direction is one-way: `storage` is byte-pure and names nothing here, so
// a cycle is a build error rather than a review comment.
lazy val trie = (project in file("modules/trie"))
  .dependsOn(bytes, rlp, crypto, types, storage)
  .settings(
    name := "fukuii-trie",
    libraryDependencies ++= testDeps
  )

// L3 -- the EVM.
//
// The edges beyond `bytes` arrive here with the phase that needs them, which is
// the world-state seam, and each is used by a type in this module rather than
// declared against one that might be:
//
//   bytes   the machine's word converts at its boundary to raw bytes, and an
//           address, a digest and a 256-bit quantity are what the seam speaks
//   rlp     a storage value is stored as the RLP of its minimal form, so the
//           codec is what the seam encodes and decodes through rather than an
//           accessory to it
//   types   an account is what the state trie answers with, and a balance and a
//           code hash are read off it
//   trie    the state a root is computed over, which the seam's one production
//           implementation is written against
//   crypto  an account created by running code is named by a digest of its
//           creator and that creator's transaction count, and one operation
//           answers with the digest of a region of memory
//
// The interface stays on this side of the seam, which is what both surveyed
// clients do -- go-ethereum declares `StateDB` inside `core/vm` and lets
// `core/state` satisfy it, and besu keeps `WorldUpdater` inside its `evm`
// module. So the edge is one-way and a second implementation costs nothing.
//
// `storage` is deliberately still absent: nothing here names a namespace or a
// key/value store, and it arrives transitively for the tests that build a state
// trie rather than being declared for a type this module does not have.
lazy val evm = (project in file("modules/evm"))
  .dependsOn(bytes, rlp, crypto, types, trie)
  .settings(
    name := "fukuii-evm",
    libraryDependencies ++= testDeps,
    // Declared HERE and not in `testDeps`, deliberately. That sequence is
    // appended to every module, and only this one reads JSON. Putting a JSON
    // parser on `bytes`'s test classpath would be a dependency with no present
    // need, which this project does not do.
    //
    // The published fixture corpora are no longer certified against the EVM
    // alone -- `DifficultyTests` is read against the proof-of-work engine --
    // and the parser still sits here rather than moving or spreading. Every
    // module that certifies against a corpus already takes this one as
    // `evm % "compile->compile;test->test"`, so the readers live beside each
    // other in one fixtures package and the modules under test consume decoded
    // values. That keeps the number of test classpaths carrying a parser at
    // one, which is the property this placement is for.
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % circeVersion % Test,
      "io.circe" %% "circe-parser" % circeVersion % Test
    )
  )

// L4 -- what settling a transaction does around the machine, and what makes a
// transaction acceptable before it runs.
//
// Every edge is used by a type here rather than declared against one this
// module might grow, which is the same test the layers below are declared by:
//
//   bytes   a sender is an address, a transaction's input is a byte string, and
//           an earlier block is named by its digest
//   types   a transaction emits logs, which is what a settlement hands back and
//           what a receipt is built from; and admission reads the signed
//           envelope itself -- its format, its chain identifier and the account
//           it recovers to
//   crypto  EIP-2 refuses a signature whose `s` is above half the curve order,
//           and half the curve order is a parameter of the curve. The crypto
//           layer exposes it for exactly this caller and documents why it does
//           not apply the bound itself: the bound takes effect at a stated
//           fork, so the layer holding that fork's rules is the one that must
//           apply it. Re-deriving the number from a curve of our own is the
//           thing that edge exists to prevent
//   evm     settling a transaction is what happens AROUND an invocation, so the
//           frame, the environment, the journal and the interpreter are what
//           this layer arranges. The prices it charges before the machine runs
//           are the machine's schedule too, which is where both authorities
//           keep them
//
// `trie` is deliberately absent. The one thing settlement needs of state that
// the machine does not -- destroying a registered account -- arrives as a
// function it calls, so nothing here names a trie or a store.
//
// It sits ABOVE evm and BELOW chainspec, and both directions follow from what
// the things are rather than from which arrived first. A settlement RUNS the
// machine, so it depends on it. A rule set is something a schedule resolves TO,
// so the module composing schedules depends on this. Each direction is one-way
// and a cycle is a build error rather than a review comment.
//
// The `test->test` half of the evm edge, for the reason chainspec declares the
// same edge below: what a settlement test needs to observe is a world state and
// a state trie built for a test, and that machinery lives in evm's test tree
// beside the machine it was written for. Duplicating a world double here would
// give one contract two implementations, and a double that drifted from the
// machine's would agree with the real one for the wrong reason.
lazy val execution = (project in file("modules/execution"))
  .dependsOn(bytes, types, crypto, evm % "compile->compile;test->test")
  .settings(
    name := "fukuii-execution",
    libraryDependencies ++= testDeps
  )

// chainspec -- which rules a network runs, and from when.
//
// It sits ABOVE the EVM and the edge is one-way, which is the boundary the
// field draws too: besu's `evm/build.gradle` names `ethereum:core` zero times
// while `ethereum/core/build.gradle` names `:evm` once. A schedule is
// configuration ABOUT the machine and holds nothing the machine executes.
//
// Two edges, each used by a type here rather than declared against one this
// module might grow:
//
//   bytes   an activation is a block number or a timestamp and a network is
//           found by its chain id -- all three are the protocol's machine
//           word, and this module names it directly rather than reaching it
//           through evm
//   evm     one of the facets a rule set populates IS the machine's rules, and
//           a component's delta is written in terms of them
//   execution
//           the other two facets -- what settling a transaction does, and what
//           admits one at all. A rule set holds all three, so this module names
//           each of them directly
//   types   which transaction FORMATS a network carries is one of the rules the
//           admission facet holds, and the format tag is a type there. The
//           earlier reading of this edge -- that `types` is absent because a
//           schedule never holds a block -- still stands and is narrower than
//           it looked: this module names the TAG and holds no header, no
//           account and no transaction. The edge is declared because a type
//           here names it, which is the same test every edge above is declared
//           by, rather than left to arrive through `execution`
// The `test->test` half of the evm edge, and why it is not a shortcut.
//
// The published fixture corpora are certified against a NETWORK's rules, so the
// mapping from a corpus to the rules it is read under belongs here. The
// machinery that reads a fixture and runs it -- the JSON decoders, the runner,
// the state seeding -- is the machine's and stays in `evm`'s test tree, which
// is also where the JSON parser is declared for the one module that reads JSON.
//
// Without this mapping the harness would have to move whole, which would make
// `build.sbt`'s own statement that only `evm` reads JSON false. With it, each
// half sits with the thing it is about, and this module's tests reach the
// machinery exactly as its main sources reach the machine.
//
// The direction is unchanged and still one-way: nothing in `evm` names anything
// here, at either scope.
lazy val chainspec = (project in file("modules/chainspec"))
  .dependsOn(bytes, types, execution, evm % "compile->compile;test->test")
  .settings(
    name := "fukuii-chainspec",
    libraryDependencies ++= testDeps
  )

// consensus -- the mechanism-neutral seam: a block's production expressed as a
// transformation over the rules a schedule resolved, plus the state change no
// transaction made. A mechanism leaf depends on this and never the reverse.
//
// It sits ABOVE chainspec, and the direction is forced rather than chosen. An
// engine reads the resolved rule set, so it depends on the module that resolves
// one; the facet it reads therefore CANNOT live here, and lives in `chainspec`
// beside the type that holds it.
//
// The seam is a module of its own rather than the first leaf, and that is the
// field's shape rather than a preference. `besu-eth/besu` @ `c2addd942`
// declares seven consensus subprojects in `settings.gradle`, with
// `consensus/clique/build.gradle` and `consensus/qbft/build.gradle` each taking
// `implementation project(':consensus:common')`; `paradigmxyz/reth` carries
// `crates/consensus/common`. The other four surveyed clients reach the same
// separation without a second build unit, by putting the seam in the PARENT
// package and each mechanism beneath it -- `ethereum/go-ethereum` and
// `ethereumclassic/core-geth` in `consensus/consensus.go` over
// `consensus/{ethash,clique,beacon}`, `erigontech/erigon` in
// `execution/protocol/rules/rules.go` over `rules/{ethash,aura,merge}`, and
// `NethermindEth/nethermind` in `Nethermind.Consensus` over
// `Nethermind.Consensus.{Ethash,Clique,AuRa}`. This module takes the second
// shape's naming and the first's enforcement.
//
// What the separation buys is structural rather than tidy: a mechanism leaf
// cannot import another leaf's types, because leaves are siblings that depend
// only on this. A guard that would otherwise be a review note is a compile
// error.
//
// `api` was rejected as the name on measurement: no surveyed client uses it for
// this seam, and it would collide with the Engine API a proof-of-stake leaf
// would genuinely carry.
//
//   bytes   a beneficiary is an address, and a reward is the protocol's
//           256-bit quantity
//   chainspec
//           the resolved rule set the transformation is over, and the facet
//           the reward application reads
//   evm     a change to state is written through `WorldState`, and the balance
//           it writes is the machine's word at that boundary
//   types   the seam names a block header, because the ommers a mechanism is
//           handed arrive as headers in every surveyed client and the two
//           facts an emission reads off one are read straight off the header
//           there
//
// `execution` is reached transitively through chainspec and is not named: what
// this module produces is the function `BlockProcessor.process` already takes,
// and producing it names no type from that module.
//
// The `test->test` half of the evm edge, for the reason execution and chainspec
// both declare the same edge: what a reward test must observe is whether an
// account came into being, and the world-state double that answers it lives in
// evm's test tree beside the machine it was written for.
lazy val consensus = (project in file("modules/consensus"))
  .dependsOn(bytes, types, chainspec, evm % "compile->compile;test->test")
  .settings(
    name := "fukuii-consensus",
    libraryDependencies ++= testDeps
  )

// consensus-pow -- the proof-of-work mechanism, as a leaf of the seam above.
//
// It is the first leaf, and the first module here whose name is not one word.
// The extra word is the MECHANISM FAMILY rather than a network, which is what
// every surveyed client keys this level on: `ethereum/go-ethereum` and
// `ethereumclassic/core-geth` under `consensus/{ethash,clique,beacon}`,
// `besu-eth/besu` @ `c2addd942` under `consensus/{clique,ibft,qbft,merge}`,
// `NethermindEth/nethermind` under `Nethermind.Consensus.{Ethash,Clique,AuRa}`.
// Ethereum Classic's emission differs from Ethereum's by a proposal rather than
// by a mechanism, so both networks are served from this one leaf and neither
// gets a module named after it.
//
// The dependency list is the whole point of the leaf being separate: it names
// `consensus` and no sibling. A second mechanism is a sibling of this rather
// than a tenant in it, so it cannot reach a type declared here, and the guard
// is the build graph rather than a review note.
//
//   bytes   a beneficiary is an address, and a seal digest is a hash
//   chainspec
//           the facet the emission reads its amount from
//   consensus
//           the seam this implements
//   crypto  the seal reaches for BOTH digests -- Keccak-256 for the header's
//           own hash, the seed chain and the final value, and Keccak-512 for
//           every row of a cache and twice per dataset item. This is the only
//           module outside `crypto` itself that names the 512-bit one, and its
//           arrival is what brought that primitive into the project
//   evm     the state a settlement writes through
//   rlp     the digest a nonce is sought against is the header's encoding with
//           the seal's own two elements removed, so this names the item type
//           and re-encodes one
//   types   an ommer arrives as a block header, and the seal is a sum this
//           mechanism must positively reject one case of
//
// crypto and rlp arrive transitively through `types` and are named anyway, by
// the same test every edge above is declared by: a source here names the type.
//
// The `test->test` half of the evm edge is the seam's own reason unchanged:
// what an emission test must observe is whether an account came into being, and
// the world-state double that answers it lives in evm's test tree.
lazy val consensusPow = (project in file("modules/consensus-pow"))
  .dependsOn(bytes, rlp, crypto, types, chainspec, consensus, evm % "compile->compile;test->test")
  .settings(
    name := "fukuii-consensus-pow",
    libraryDependencies ++= testDeps
  )

// The aggregate. `aggregate` makes a task at the root fan out to every module;
// it is NOT a dependency edge, so the root gains nothing on its classpath.
//
// The PROJECT is fukuii. `fukuii-cli` is the repository's name, not the
// project's, and this setting is the artifact coordinate — it publishes as
// org.fukuii:fukuii_3, so the repo name must not leak into it. Lowercase
// because this is a Maven artifactId, not a display name.
lazy val root = (project in file("."))
  .aggregate(bytes, rlp, crypto, types, storage, trie, evm, execution, chainspec, consensus, consensusPow)
  .settings(
    name := "fukuii",
    libraryDependencies ++= testDeps
  )
