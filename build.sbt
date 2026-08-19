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
//
// The -release pair is set inside the single scalacOptions assignment below
// rather than in its own statement, for the reason that assignment documents.
ThisBuild / javacOptions := Seq("--release", "25")

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
// nonces and low-S canonicalization, secp256r1, BLAKE2F, and a constant-time
// comparison primitive. The JDK supplies none of the first four.
//
// 1.85 is separately a security release; that selects the VERSION, not the
// dependency. Its published POM declares NO dependencies at all, so it arrives
// alone. Gate 0 does not bind it — it is a Java artifact, confirmed by the
// absence of any TASTy entry in the shipped jar rather than inferred from the
// coordinate's missing _3 suffix.
//
// 1.85.2 exists and is deliberately not taken: it is inside the release-age
// cooldown, and a commit-by-commit read of the interval found nothing touching
// keccak, secp256k1, secp256r1 or BLAKE2F. Revisit on ordinary currency.
lazy val bouncyCastleVersion = "1.85"

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
  .dependsOn(bytes, rlp, types, trie)
  .settings(
    name := "fukuii-evm",
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
  .aggregate(bytes, rlp, crypto, types, storage, trie, evm)
  .settings(
    name := "fukuii",
    libraryDependencies ++= testDeps
  )
