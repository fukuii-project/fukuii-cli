# fukuii-cli — agent instructions

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

**No library dependencies are declared yet, and that is deliberate.** Each one
is added when a real need for it arises, and adding one means answering: what
problem does this solve, why this over the alternatives, why this version, and
what would change the answer. **A dependency with no present need is not an
entry** — do not add one because another project has it, or because an older
build did.

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

Nothing to install yet — there is no build tool, dependency manager, or
toolchain configuration on this branch.

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
```

**Run `sdk env` first, or you are not building against the declared toolchain.**
`.sdkmanrc` names the JDK; without applying it, sbt runs under whatever JDK the
shell happens to provide, which may be a different vendor or major version.
`sdk env install` first if that JDK is not present.

**`compile` is sbt's own task, not one this project defines.** The build adds no
custom tasks. `test` will work once tests exist; it is not listed because there
is nothing yet for it to run.

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

**No test source exists on this branch yet — every row below is the assignment
tests will be written to, not a description of tests that exist.** The policy is
settled; nothing has been built against it here. Anything in this section stated
as a measurement was measured elsewhere and says so.

ScalaTest ships eight styles so a *project* can fit a style to each use case —
not so each contributor can pick a favourite. This project's assignment:

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

**This is a build-level policy, not yet a build-level enforcement.** No
`build.sbt` or `project/Dependencies.scala` exists on this branch — the stack is
decided and pinned in a later phase of this repository's onboarding. Once a
build exists, it must enforce this policy by naming the individual style
artifacts, never the `scalatest` aggregate:

```scala
"org.scalatest" %% "scalatest-flatspec" % scalatestVersion % Test
"org.scalatest" %% "scalatest-propspec" % scalatestVersion % Test
```

An unlisted style should then not resolve, so using one becomes a compile error
rather than a review comment. **Adding a style artifact is a policy change** —
it belongs in the commit that introduces the first legitimate use of it, with
the use case named. Do not add one to make a single file compile.

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

Use the **capitalised** `Feature` / `Scenario`. The lowercase `feature` /
`scenario` was deprecated in ScalaTest 3.1.0 and is slated for removal, though
much ScalaTest documentation still shows the old form. **Re-confirm against the
version actually pinned when a build lands** — this branch pins no ScalaTest
version, so the "still compiles on 3.2.20" half of this could not be checked
here.

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

**Branch first.** Work goes on a topic branch — a conventional prefix (`feat/`,
`fix/`, `refactor/`, `test/`) plus a short kebab-case description — and reaches
`main` by pull request. `main` must stay releasable.

No branch protection is configured on `main` yet — with a single developer,
that safeguard is scheduled to land once a second primary builder joins and
CI/testing begins. Until then this is a followed convention, not a
GitHub-enforced gate.

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
   remove it, and never propose a different licence. The question is legal, not
   technical.
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
   Claude's own file tools and the Bash file commands it recognises (`cat`,
   `head`, `tail`, `sed`); they do **not** reach an arbitrary subprocess that
   opens a file itself, such as a Python or Node script.

   Removing an entry is a security change, not a convenience fix. Ask first.

<!-- Add boundaries as the repository grows. The highest-value entries are the
     ones whose breakage is invisible from inside this repo: generated
     artifacts, paths other repos consume directly, and anything with a
     downstream consumer. -->
