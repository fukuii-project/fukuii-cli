# Fukuii — agent instructions

<!--
  DEFAULT WIRING. The content sections below are deliberately unfilled.

  Fill each one from what this repository actually contains — never from what a
  comparable project usually contains. Anything written here is read as fact by
  every agent that opens the repo, so an unverified placeholder is worse than an
  empty heading. Delete a heading this project does not need rather than
  inventing content for it.

  Format: plain Markdown, no YAML frontmatter. This is the cross-tool AGENTS.md
  standard (https://agents.md), read by many agents besides Claude, and it
  belongs at the repository root and nowhere else.

  Keep this file and .github/copilot-instructions.md from contradicting each
  other. Several Copilot surfaces read one and not the other, so both have to
  stand alone, and a contradiction between them is a contradiction the model
  cannot resolve.
-->

## Overview

<!-- What this project is, in two or three sentences: what it does and who runs
     it. Leave this unwritten until there is a settled answer — a description
     invented here becomes a public claim the moment the branch merges. -->

## Stack

<!-- Language, runtime and build-tool versions, read from this repo's own
     manifest and lockfile rather than from any external version list. Record
     the major series, not an exact patch: patch numbers go stale within days,
     and the lockfile is the place that carries them accurately. -->

| | Version | Declared in |
|---|---|---|
| Scala | **3.3.x LTS** (3.3.8) | `build.sbt` |
| JDK | **25 LTS**, Eclipse Temurin | `.sdkmanrc` |
| sbt | **2.0.x** (2.0.4) | `project/build.properties` |
| ScalaTest | **3.2.20**, `Test` scope only | `build.sbt` |
| Pekko | **1.6.x** (1.6.0) — *decided, not yet declared* | — |

**Scala is on the LTS line, and that is line membership rather than a version
preference.** 3.8.x is *Scala Next*, a different line this project does not run —
not a higher number that was skipped. Stated as a numeric comparison the
reasoning needs re-arguing every time the numbers interleave.

**The rule for every dependency: current LTS; where no LTS exists, latest
stable; never exploratory releases, never head-of-branch.** An LTS designation
means maintainers have committed funded support for a defined period, which
keeps this project's dependencies supported over a meaningful horizon and holds
churn down.

**The JDK distribution is named deliberately.** End-of-life dates differ between
distributions by years, so a JDK version without a distribution does not say
what is actually supported. `.sdkmanrc` pins both; run `sdk env` to apply it, or
`sdk env install` first if the JDK is absent.

**No compile-scope library dependency is declared, and that is deliberate.**
Each one is added when a real need for it arises, and adding one means
answering: what problem does this solve, why this over the alternatives, why
this version, and what would change the answer. **A dependency with no present
need is not an entry** — do not add one because another project has it, or
because an older build did. ScalaTest, above and in `## Testing` below, is the
first dependency to answer those four questions, and it is `Test`-scope only.

**One constraint binds every library added here.** Scala 3 guarantees that LTS
output can be consumed by newer Scala Next, but **not the reverse** — so a
library published only for Scala Next is unusable on this project's 3.3.x, not
merely newer. Check that before anything else about a candidate; see
`.claude/rules/scala-dependency-admissibility.md`, which also explains why the
artifact coordinate alone cannot answer it.

There is still no dependency-update (Dependabot) configuration. That was correct
while no manifest existed and is now a gap to close as the dependency set grows.

## Setup

<!-- What a contributor installs before anything works, and how they verify the
     toolchain is correct. -->

Two things: a JDK matching the one this repo declares, and sbt.

```
sdk env install    install the declared JDK if it is absent
sdk env            apply it to this shell
sbt compile        verify the toolchain resolves and builds
```

**The JDK is declared by version *and* distribution** in `.sdkmanrc`, because
end-of-life dates differ between distributions by years — a version alone does
not say what is supported. [SDKMAN](https://sdkman.io) reads that file; any
other installation method works equally well provided it produces the same JDK.

**`.sdkmanrc` is a declaration, not a mechanism.** It applies automatically on
`cd` only where SDKMAN's `sdkman_auto_env` is enabled, and that setting is off
by default. Without it, run `sdk env` per shell — otherwise the build silently
uses whatever JDK the shell provides.

sbt needs no separate pin: `project/build.properties` names the version, and the
sbt launcher reads that file and fetches it.

## Commands

<!-- Copy the real task names verbatim out of this repo's own build definition.
     Every command listed here must exist — an agent told to run a task the repo
     does not define will run it, fail, and report a broken build.

     State absent tooling AS ABSENT. "There is no lint or test task in this
     repo" is load-bearing: it tells an agent to match style by hand instead of
     trusting a gate that is not there. Silence reads as "look harder". -->

```
sdk env            apply the JDK this repo declares (once, per shell)
sbt compile        compile
sbt test           run tests — may report success having run none or only
                   some of them, see below
sbt testFull       run tests uncached; the one to trust for a pass/fail claim
```

**Run `sdk env` first, or you are not building against the declared toolchain.**
`.sdkmanrc` names the JDK; without applying it, sbt runs under whatever JDK the
shell happens to provide, which may be a different vendor or major version.
`sdk env install` first if that JDK is not present.

**`compile`, `test` and `testFull` are sbt's own tasks; the build defines none
of its own.**

**Use `testFull` before treating a run as evidence anything passed.** sbt 2
caches the test result machine-wide, and that cache survives both `clean` and
copying the project to a new directory. This is documented, intended behavior
rather than a defect: sbt's own reference calls the result *"cached
machine-wide"* and names `testFull` as the uncached equivalent of sbt 1's
`test`.

**The cache is per-suite and keyed on inputs, and that is what makes `test`
misleading rather than merely useless.** With nothing changed it executes
nothing and reports success. Change one file and it executes **only the suite
that file belongs to**, then reports *"All tests passed"* over a partial run.
The loud failure is the zero; the quiet one is the partial, and the partial is
the one that gets believed.

**So check the executed test count against the EXPECTED TOTAL** — not the exit
code, and not merely against zero. A non-zero count is not evidence of a full
run. Count tests, not files: a suite is a class, a test is one assertion block
inside it, and one class commonly carries several, so the expected total is
larger than the number of spec files.

**There is no `lint`, `format`, `typecheck` or coverage task, and no CI.** Match
the style of surrounding code by hand rather than relying on a gate that does
not exist. Do not invent a command — one that the build does not define will
fail and read as a broken build.

## Structure

<!-- The actual directory tree, one line of purpose per entry. Prune it to what
     an agent needs to navigate; a full listing ages badly. -->

## Testing

<!-- How tests are run, what must pass before a change lands, and which failures
     are known and expected. Not yet written — the style policy below is
     settled and the rest of this section is not. -->

### Test style is assigned by use case, not by author

**A toolchain-proof spec exists for each declared style artifact** — `AnyFlatSpec`,
`AnyPropSpec`, `AnyFeatureSpec` — at `src/test/scala/org/fukuii/Toolchain*.scala`,
each proving its artifact resolves and that the runner reports a real result.
**They are not evidence the use cases below have been exercised:** there is no
application code, so each tests the toolchain itself and carries its own
retirement trigger in its Scaladoc. Anything in this section stated as a
measurement was measured elsewhere and says so.

ScalaTest ships eight styles so a *project* can fit a style to each use case —
not so each contributor can pick a favorite. This project's assignment:

| Use case | Style | Status |
|---|---|---|
| Unit tests | `AnyFlatSpec` | the default; applies from the first test written |
| Integration tests — real database, real network peer | `AnyFlatSpec` | when L5+ lands |
| Property checks | `AnyPropSpec` | when a property check is first written |
| Test matrices — one table of named vectors, one expected outcome | `AnyPropSpec` + `TableDrivenPropertyChecks` | when a vector table is first written |
| Acceptance tests — behavior stated in domain language | `AnyFeatureSpec` + `GivenWhenThen` | when a stakeholder-facing requirement needs one |

Everything else — `FunSuite`, `FunSpec`, `WordSpec`, `FreeSpec`, `RefSpec` — is
**not used here**. See the rejections below before proposing one.

Unit and integration share `AnyFlatSpec` deliberately: ScalaTest recommends
writing integration tests in the same style as the unit tests, and reserving the
gear-change for acceptance. The split that matters is unit/integration vs
acceptance, not unit vs integration.

**This is a build-level policy, and `build.sbt` enforces it** by naming the
individual style artifacts, never the `scalatest` aggregate:

```scala
"org.scalatest" %% "scalatest-flatspec"    % scalatestVersion % Test
"org.scalatest" %% "scalatest-propspec"    % scalatestVersion % Test
"org.scalatest" %% "scalatest-featurespec" % scalatestVersion % Test
```

An unlisted style does not resolve, so using one is a compile error rather than
a review comment.

**Declaring the assigned set is implementing this policy, not changing it.** All
three belong in the commit that pins the framework, rather than arriving one at
a time as each style's first use appears. A build declaring fewer than the
assigned three would enforce a *narrower* policy than this document publishes —
a silent divergence between the standard and the mechanism meant to enforce it.
Pinning `scalatest-funsuite` alone once made `PropSpec` impossible to compile
and foreclosed the exact use case ScalaTest designed it for; **before adding or
removing a style artifact, check which use cases the change makes impossible.**

**Adding a style BEYOND this set is the real policy change** this rule guards
against. It belongs in the commit that introduces the first legitimate use of
that style, with the use case named. Do not add one to make a single file
compile.

### Writing each style

**`AnyFlatSpec`** — the subject is the unit under test, the predicate is an
infinitive. Many subjects per class; `it` continues the current one.

```scala
"fromBytesTruncating" should "left-pad a short input" in { ... }
it should "keep the rightmost 20 bytes of a long input" in { ... }

"toPrefixedHex" should "be 0x followed by 40 hex chars" in { ... }
```

One subject per class with everything else hanging off `it` throws away the
reason for choosing FlatSpec. A class *may* legitimately have one subject when
it genuinely tests one thing.

**`AnyPropSpec`** — a `*PropSpec.scala` beside the FlatSpec it split from, each
naming the other in its Scaladoc. Property files hold no fixed examples and
example files hold no `forAll`.

**Assertions: `assert(cond, clue)`, never matchers,** in every style. A bare
`assert` failure prints nothing actionable. Matchers are a separate dependency
and a second dialect for no gain.

### Why the other five are rejected

Recorded so each is settled once. A proposal to adopt one needs to answer the
specific objection, not restate the style's general merits.

- **`FunSuite`** — the pre-rebuild tree's L0 suite was converted *out* of it.
  (That tree is not this branch; the conversion is why the policy exists, not
  something you will find here.) It permits `test("some prose")` with no
  subject, which is how test names drift into restating the assertion.
  FlatSpec's grammar makes that a compile-time shape, not a review comment.
- **`FunSpec` / `WordSpec` / `FreeSpec`** — all three nest. Nesting earns its
  cost when shared setup is scoped to a context; a codec, a hash and a curve
  have no such context. FreeSpec additionally gives no guidance on structure,
  which is the wrong property for a codebase several agents write into.
- **`RefSpec`** — its advantage is real (tests as methods, so fewer function
  literals and faster compiles on generated suites, which the L10 Ethereum
  reference-test harness could want). It conflicts with the warning ratchet
  planned for this repo — no ratchet is configured on this branch, because no
  build is. Measured in the pre-rebuild tree on Scala 3.3.8 / ScalaTest 3.2.20,
  neither of which is pinned here yet:

  | test-method body | result |
  |---|---|
  | `def x(): Unit = assert(...)` | **E175** discarded non-Unit value |
  | `def x(): Unit = { assert(...); () }` | **E176** unused value of type `Assertion` |
  | `def x(): Unit = assert(...): Unit` | compiles and runs |

  So RefSpec costs a `: Unit` ascription on every test method, or a ratchet
  exemption. If the reference-test harness later shows a real compile-time
  problem, revisit with that measurement in hand — the ratchet is the thing to
  weigh it against, and generated code can emit the ascription for free.

### FeatureSpec, when it arrives

Use the **capitalized** `Feature` / `Scenario`. The lowercase `feature` /
`scenario` was deprecated in ScalaTest 3.1.0 and is slated for removal, though
much ScalaTest documentation still shows the old form. **Confirmed against the
pinned ScalaTest 3.2.20** — `ToolchainFeatureSpec.scala` is written in exactly
this form and compiles and runs, so a change in that syntax becomes a build
failure rather than a stale instruction.

```scala
class ThingSpec extends AnyFeatureSpec with GivenWhenThen:
  Feature("..."):
    Scenario("..."):
      Given("...")
      When("...")
      Then("...")
```

## Code style

<!-- Indentation, line endings, naming conventions, import ordering — and for
     each, whether a formatter or linter enforces it or it is convention only.
     An agent needs to know which rules are checked and which are trusted. -->

## Branching

**This is a single-developer project, and the branch-and-pull-request policy is
NOT in effect.** Work happens on a local branch merged into `main`, or directly
on `main`. **`main` is the only published branch.** Do not open a pull request,
and do not treat the absence of one as drift.

**The policy that activates later, and the signal that activates it.** Once the
project is built publicly with multiple participants — expected when testing
begins after the rebuild — work moves onto topic branches reaching `main` by
pull request, `main` stays releasable, and branch protection is configured.
**The operator states when that is in effect. Do not infer it** from the
presence of contributors, of CI, or of a protected branch.

**When a local branch is used, name it conventionally:** a prefix plus a short
kebab-case description. Use the same set this repository uses for commit types —
`feat/`, `fix/`, `refactor/`, `test/`, `build/`, `docs/`, `chore/`.

Pushing is a separate decision from committing. Never push unasked.

## Security

This repository is **public**.

- **`.gitignore` is the gate** between what stays local and what the world sees.
  Never weaken it. If a needed file is being ignored, say so rather than
  removing the pattern that covers it.
- **Never add a secret, key, credential, keystore or `.env` file.** An
  `.env.example` is deliberately visible and must contain placeholders only,
  never a real value.
- **Report a suspected exposure; do not quietly clean it up.** Deleting a
  committed secret leaves it retrievable from history, so the response is
  rotation, not removal, and that is a human decision.

## Boundaries — ask before touching

1. **`LICENSE` is Apache-2.0 by deliberate choice.** Never change, replace or
   remove it, and never propose a different license. The question is legal, not
   technical.

   **`NOTICE` carries the attribution Apache-2.0 §4(d) requires, and it is
   deliberately minimal. Never edit its legal language, and never expand it.**
   It states non-derivation once and names Mantis only for history, etymology
   and lore. That brevity is the decision, not an omission: **over-explaining in
   a legal context is itself a signal**, and a longer NOTICE reads as a weaker
   one. A more discursive version exists in this project's history and was
   deliberately condensed — restoring material from it is a regression, not a
   completion.
2. **Community-health files are inherited, not missing.** The `fukuii-project`
   organization supplies `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`
   and the issue and pull-request templates to every repository that lacks its
   own. Do not add local copies.
3. **`.claude/` is tracked by default — a deny-list, not an allow-list.**
   `.gitignore`'s Claude Code block excludes only specific machine-local and
   agent-written paths (`settings.local.json`, `worktrees/`,
   `agent-memory-local/`, `launch.json`, `scheduled_tasks.json`,
   `CLAUDE.local.md`, and anything matching `*.local.*` or `*-local/`).
   Everything else under `.claude/` — `hooks/`, `rules/`, `settings.json`, and
   any future subdirectory such as `agents/`, `skills/` or `commands/` — is
   tracked and reaches a clone. Adding a file under `.claude/` is public by
   default; check `.gitignore`'s current deny-list before assuming a path is
   held back, and never invert this into a blanket ignore — an agent, skill, or
   rule inside an ignored directory never reaches a clone.
4. **`.claude/settings.json` denies reads of exactly seven patterns** — `.env`,
   `.env.*`, `secrets/**`, `*.pem`, `*.key`, `*.keystore`, `*.p12`. **It is not a
   general "key material is protected" guarantee**, and reading it as one is the
   failure this wording exists to prevent. Classes `.gitignore` treats as key
   material and this list does **not** cover include `UTC--*` (the geth/mantis
   keystore filename convention), `wallet.json`, `mnemonic.txt`, `*.jks`,
   `*.pfx`, `id_rsa`/`id_ecdsa`/`id_ed25519`, `.netrc`, `.git-credentials`,
   `credentials.json`, `jwt.hex`, `jwtsecret` and `*.nodekey`. Treat those as
   unprotected by this mechanism and handle them accordingly.

   Two further limits, both documented rather than incidental. The patterns are
   `./`-anchored, which resolves against the **current directory**, not the
   project root — so the guarantee holds for a session started at the repository
   root and narrows for one started in a subdirectory. And deny rules reach
   Claude's own file tools and the Bash file commands it recognizes (`cat`,
   `head`, `tail`, `sed`); they do **not** reach an arbitrary subprocess that
   opens a file itself, such as a Python or Node script.

   Removing an entry is a security change, not a convenience fix. Ask first.

<!-- Add boundaries as the repository grows. The highest-value entries are the
     ones whose breakage is invisible from inside this repo: generated
     artifacts, paths other repos consume directly, and anything with a
     downstream consumer. -->
