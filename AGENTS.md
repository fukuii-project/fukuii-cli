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

**So check the executed test count against the expected total** — not the exit
code, and not merely against zero. **A non-zero count is not evidence of a full
run.**

**Read the expected total from a `testFull` run's own `Total number of tests
run` line, not by counting from source.** `testFull` bypasses the cache, so its
count is the one figure that cannot itself be a partial. Counting by hand is
where this goes wrong.

**Count registered test blocks — not files, and not assertions.** A suite is a
class. A test is one registered block: `"subject" should "..." in { }`,
`property("...") { }`, `Scenario("...") { }` — however many assertions execute
inside it. **A table-driven `property` is ONE test however many vectors it
drives**, which matters because `## Testing` assigns exactly that shape to
vector tables. One class commonly carries several tests, so the expected total
is larger than the number of spec files.

**Two more shapes in which sbt reports success having done nothing. All three
are the same family, and the other two are not about the test cache.**

**A stale detached sbt server answers without rebuilding.** sbt's persistent
server does not reload `build.sbt`, `project/*.scala` or
`project/build.properties` on its own. A server left running from before a
build-definition edit is serving a stale settings graph, and will answer
`clean`, `compile` and `Test/compile` with a fast `[success]` having recompiled
nothing. If a build-definition change appears to have had no effect, suspect the
server before the change. **Do not use `show <mod>/Compile/compile`'s
`Analysis: N Scala sources` line to judge staleness** — it does not reliably
report the target module's own scope, even against a fresh server.

**A `project <id>` selector followed by chained tasks runs only the switch.**
`"project foo" "clean" "compile"` performs the project switch, never runs the
tasks, and still exits 0. **Use module-scoped `<mod>/<task>` syntax** —
`foo/clean`, `foo/compile` — which has no such failure mode and needs no switch.

**Checking whether `target/` changed does not detect either one.** Any sbt
invocation, including a bare project switch, touches `target/global-logging` and
each project's `streams`, `update` and `meta` directories. Only the real
compile-output paths are evidence of a real build.

**And the residual, because the guard above is narrower than it looks.** A
hollow run with no `clean` and no project-lead selector is **indistinguishable
from a legitimate incremental no-op** — both do nothing and both exit 0. Nothing
detects that case, so a green from an incremental run is weaker evidence than a
green from a clean one. When a result has to be trusted, make it a clean run.

> **Inherited and unverified.** Both were observed in this project's prior
> implementation on 2026-07-16, against a multi-module layout this repository
> does not yet have, and neither has been reproduced here — this repo has one
> module and no `src/main`. They are recorded because both are properties of sbt
> rather than of that codebase, and because the cost of rediscovering them is a
> debugging session spent trusting a green.

**A suite that failed is never skipped.** `test` re-runs whatever failed on the
previous run, so it cannot report a green over a prior failure. The trap is
confined to the passing direction.

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
  this repository intends — **the ratchet is not configured yet**, and
  `.claude/protocols/warning-ratchet.md` is why that matters: a category gated
  before the code exists is free, and gated afterwards is a migration.
  Measured in the pre-rebuild tree on **the same Scala 3.3.8 and ScalaTest
  3.2.20 this repository now pins**, so the measurement applies directly rather
  than by analogy:

  | test-method body | result |
  |---|---|
  | `def x(): Unit = assert(...)` | **E175** discarded non-Unit value |
  | `def x(): Unit = { assert(...); () }` | **E176** unused value of type `Assertion` |
  | `def x(): Unit = assert(...): Unit` | compiles and runs |

  So RefSpec costs a `: Unit` ascription on every test method, or a ratchet
  exemption. If the reference-test harness later shows a real compile-time
  problem, revisit with that measurement in hand — the ratchet is the thing to
  weigh it against, and generated code can emit the ascription for free.

### Writing a test — three standing rules

**No `Thread.sleep`.** Wait on the condition, not on the clock — an
`eventually`-style retry or a probe expectation. A sleep is either too short and
flaky or too long and slow, and it is usually both on different machines.

**No skipped test without a stated gate condition.** A one-line reason naming
what would re-enable it. A test skipped with no condition is a deleted test that
still shows up in the count.

**One behavior per test, not a scenario script.** A test that walks through six
steps fails at step four and tells you almost nothing; six tests tell you which
one broke.

**Deterministic across machines.** No dependence on wall-clock time, filesystem
ordering, locale, or an available port.

### Before adding a spec, check whether an existing one extends

Tests that differ only by input and expected output belong in **one table-driven
test**, not N near-identical blocks — which is what `AnyPropSpec` plus
`TableDrivenPropertyChecks` is assigned to above. Shared setup or assertion
logic goes in a helper; the test body keeps whatever makes that case unique, so
a reader is not chasing behavior into a fixture.

**The tell:** several `should` blocks in one file whose bodies differ only in
literals.

### The test count only goes up without a recorded reason

Record the executed test count before a change and compare after. **A negative
delta — even by one — means a test was silently dropped**, most often when a
spec is deleted during a rewrite and its replacement is never written. Do not
accept a lower count as the new baseline without stating why it is correct.

**This is a ratchet, not a reading method.** `## Commands` owns how to obtain a
trustworthy count and what to compare it against — including why a `testFull`
run is the only figure that cannot itself be partial. This rule adds one thing
to that: the number it produces must not fall between runs.

The two catch different failures with the same instrument. There, a count below
the expected total means **this run** was partial. Here, a count below the
**previous run** means the suite is.

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

**Nothing here is enforced by a tool.** There is no formatter, no linter, no
static-analysis config and no CI in this repository — `## Commands` is the
authority for what exists. Every rule below is checked by review, so it binds
by being read.

**Three rules carry the detail. They are the authority; this section is the
map.**

| Rule | Governs |
|---|---|
| `.claude/rules/scala3-style.md` | Scala 3 idiom, design rules, `given`/`using` semantics, logging conventions, and the lint prohibitions to switch on when a linter first lands |
| `.claude/rules/nomenclature.md` | The vocabulary a name is drawn from — identifiers, types, and prose |
| `.claude/rules/comment-content.md` | What a comment is for, and what must never appear in one |

Each is scoped to `**/*.scala`, so it loads when Scala is opened and **not**
during design discussion. The two rules below are the part that must reach you
before a file exists, so they are stated here, where they load every session.

**A name is chosen once and read forever, so choose it from the registry, never
from a prior implementation.** An earlier attempt tells you where something
lived and what shape it was; it is not an authority for what a thing is
*called*. A name that fits reads as a name that was chosen, which is why this is
harder to catch than a wrong value. `.claude/rules/evidence-and-citation.md` §4
owns the rule; `.claude/rules/nomenclature.md` says what to draw from instead.

**Fix what is in the file you already opened; do not chase.** A cheap, obvious
fix in a file you are editing is worth making. Following it into callers or
related files is how a scoped change becomes an unreviewable sweep — record what
you saw elsewhere instead. Cleanup commits separately from the work that opened
the file; the risk split that governs how is house commit convention, and it is
not restated here.

**Comments explain why, never what.** Code says what it does. A comment that
narrates the code restates it and then rots independently of it. Never record
migration provenance, a rebuild's history, or an agent's working notes in
source — that material belongs to this project's records, not to a file that
ships. **Build definitions are a different register**: in `build.sbt` and
`project/`, rationale *is* the content, and it must not be stripped as narration
nor carried into `src/`.

## Protocols

`.claude/protocols/` holds this repository's own operating discipline — each one
a moment, an actor, and a trigger. **The directory listing is authoritative, not
this table.**

**These do NOT load automatically. `.claude/rules/` does; this directory does
not**, so nothing puts a protocol in front of you at the moment it applies. Read
the one that matches before you act, not the ones you happen to remember.

| Protocol | The moment it governs |
|---|---|
| `dead-code-review.md` | Before deleting code that looks unused |
| `warning-ratchet.md` | Configuring a lint or warning category — the window closes once code exists |
| `scope-boundary.md` | A scoped task that appears to need work outside its scope |

**Where a protocol carries a fact you cannot afford to miss, that fact is also
in a rule that loads on its own** — so the protocol holds the procedure and the
rule holds the trap. `dead-code-review.md` is the worked case: its hazard, that a
`given` can have zero textual references and still be live, is stated in
`.claude/rules/evidence-and-citation.md` §3, which loads every session.

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

**One repo-local rule is held pending this same signal.**
`.claude/rules/comment-content.md` defers its inline `#NNNN` issue-and-pull-request
citation form until the pull-request workflow is in effect; the operator's
declaration above is the only thing that activates it.

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
4. **`.claude/settings.json`'s read-deny list is short and specific. It is NOT a
   general "key material is protected" guarantee**, and reading it as one is the
   failure this wording exists to prevent. **Read the list from
   `.claude/settings.json` — that file is the authority**, and any list written
   here would be a second copy going stale the moment the first one changes.

   **The invariant that matters is the asymmetry, not the contents:
   `.gitignore` covers materially more key-material classes than the deny list
   does.** `.gitignore` stops a file being **committed**; the deny list stops it
   being **read**. So a file can be safely un-committable and still readable into
   agent context — from where it reaches reports, commit messages and subagent
   prompts. Node-operator material is the live case: keystore files, wallet and
   mnemonic exports, SSH and JWT secrets, node keys. **Assume a path is readable
   unless you have checked `.claude/settings.json` and found it covered.**

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
