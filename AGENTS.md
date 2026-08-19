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

**Fukuii is an independent, ground-up Scala execution client for the EVM
ecosystem** — one binary meant to run more than one network family, each
behind a shared execution engine and a pluggable consensus module, rather than
a client forked and adapted per chain. **The networks named here are scope, not a
support claim — none of them runs yet.** The near targets that shape the build
today are proof-of-work networks (Ethereum Classic mainnet, Mordor) and
proof-of-stake networks (Ethereum mainnet, Sepolia). The wider targets, named
because they constrain the **seams** rather than the schedule, are the other
EVM-equivalent networks — Optimism and the OP Stack, Polygon PoS, the
proof-of-authority family already surveyed in `.claude/protocols/`, and private
networks and devnets.

**Naming the wider set costs the build almost nothing and constrains the shape a
great deal, which is why it is here rather than deferred to the section that
first needs it.** Measured across the reference corpus: an EVM-equivalent network
varies the opcode table, the gas schedule and the precompile set, and touches the
interpreter nowhere else. `ethereum-optimism/op-geth` carries chain-specific
tokens in exactly one file under `core/vm`, and that file is the precompile
registry; `ava-labs/subnet-evm` carries none; `ronin/ronin`'s three are all added
precompiles. **So the multi-network seam and the fork seam are one seam** — and the
field's answer to both is a **baseline plus per-proposal deltas**, driven by a
chain configuration rather than by per-named-fork branching.
`ethereumclassic/core-geth` is the worked case: `core/vm/jump_table.go:73`
starts from `newBaseInstructionSet()`, commented at `:244` as *"returns Frontier
instructions"*, then applies conditional blocks over it — and those deltas are
mostly **repricings in place**, not additions, its EIP-150 block being seven
`constantGas` reassignments and zero insertions. It has that shape because it
serves ETC and ETH-like chains from one binary.

**zkEVMs and Arbitrum are excluded, and the exclusion is a decision rather than an
omission.** They change the machine itself — Scroll disables `SELFDESTRUCT` in its
jump table, Polygon's CDK line ships a parallel `*_zkevm.go` interpreter — so
admitting one is a different question from admitting the networks above, and it
has not been asked. **Do not read the wider list as licensing them.**

This is a from-scratch rebuild carrying no code from any
prior implementation, and it started from a pinned toolchain. The layers built
so far sit under `modules/`, rising from the foundation through the typed
values every higher layer speaks to the commitments a block header carries —
**read which ones from the tree rather than from this sentence, with the command
below.** A prose roster of the built set is the same stale artifact as a
written one, and this sentence has already outlived two layers landing.

**`modules/` holds a directory per PLANNED layer, most of them empty, and that
is deliberate.** Git does not track an empty directory, so a placeholder is
untracked and reaches no clone — expected, and not a defect to fix. **Do not
delete them, and do not report their number as a finding**; that has been
re-reported by session after session, each one re-deriving the same listing and
reaching the same wrong conclusion.

**So read the BUILT set with
`git ls-files 'modules/*' | cut -d/ -f2 | sort -u`, never with `ls modules/`.**
The plain listing mixes planned layers in with built ones and reads as though
the whole client existed. No roster is written here on purpose: one goes stale
the next time a layer lands.

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
| ScalaCheck | **1.19.x**, with the `scalacheck-1-19` bridge, `Test` scope only | `build.sbt` |
| BouncyCastle | **1.85** (`bcprov-jdk18on`), compile scope | `build.sbt` |
| Pekko | **1.6.x** (1.6.0) — *decided, not yet declared, and it does NOT reach the foundation layer* | — |

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

**Every dependency is added when a real need for it arises, and adding one means
answering: what problem does this solve, why this over the alternatives, why
this version, and what would change the answer. A dependency with no present
need is not an entry** — do not add one because another project has it, or
because an older build did.

**`build.sbt` is the authority for what is declared, and this table is a
summary that ages.** Where they disagree, believe the build. The compile-scope
set is deliberately small; the cryptography provider is the only entry so far,
and it earned it against a named list of primitives rather than as a
general-purpose utility.

**One constraint binds every library added here.** Scala 3 guarantees that LTS
output can be consumed by newer Scala Next, but **not the reverse** — so a
library published only for Scala Next is unusable on this project's 3.3.x, not
merely newer. Check that before anything else about a candidate; see
`.claude/rules/scala-dependency-admissibility.md`, which also explains why the
artifact coordinate alone cannot answer it.

**Read proof-of-stake Ethereum first, and Ethereum Classic as the downstream
addition.** ETH is the leading EVM network and where EVM development happens;
proposals land, get implemented and get exercised there first, and **ETC
historically lags it**, so a design derived from ETC first inherits that lag. The
order is the executable specification, then `go-ethereum` as the largest
production client, then `besu` as the largest production JVM client — whose
*shape* is the most directly transferable here. **Then** ETC as additive:
`core-geth` for what ETC runs, `besu-etc` as a reference build rather than a
mainstream client, and **`go-ethereum-pow` at `v1.10.26` — geth while it still ran
proof-of-work**, which is where much of ETC's proof-of-work behavior has its
clearest expression and which current `master` no longer contains at all.

**This does not weaken ETC's authority where ETC is the authority.** A value ETC
adjusts is the ECIP's, and a mechanism only ETC specifies is ETC's alone. The
order governs the default reading path for what the two families *share*, which is
most of the EVM. `.claude/reference-corpus.md` § "Reading order" carries the full
statement and the reason it was written.

**The reference repositories this project cites are listed in
`.claude/reference-corpus.md`** — a manifest of public URLs and refs, so the
reference corpus can be rebuilt and a protocol claim checked against the same
sources independently. A repository listed there is evidence about how something
behaves, never an adopted dependency; this table remains the authority for what
is declared.

**Whether a dependency-update (Dependabot) configuration exists for `build.sbt`
is answered by looking for `.github/dependabot.yml`, not by this paragraph.**
What is durable is why one is expressible and what it would and would not
cover. `build.sbt` is a real manifest, and GitHub's Dependabot has
carried a dedicated [`sbt` ecosystem](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories#sbt)
since it [shipped 2026-05-26](https://github.blog/changelog/2026-05-26-dependabot-version-updates-now-support-the-sbt-ecosystem/);
it fetches `build.sbt` at the repository root as a required file. Coverage has
a real limit, though: GitHub's own wording is *"This applies to version
updates, not security updates,"* so a configured `sbt` entry opens scheduled
pull requests for newer upstream releases but does not plug into Dependabot's
advisory-triggered security-update path the way some other ecosystems do.

**So an entry for this manifest is expressible, and it is GATED — not merely
undecided.** The operator has deferred it until the rebuild completes and this
repository is organized for public contributions and release, which is the same
signal `## Branching` names and is declared by the operator alone. **Until that
is declared: do not write one, do not propose one as ready work, and do not
report its absence as drift or as a finding.** A live config starts opening pull
requests on the next scheduled run — outward-facing, against a release process
that does not exist yet and a branch policy explicitly not in effect.

**Do not reduce this back to "it is the operator's call."** That framing is true,
omits the gate, and reads as unblocked — which is why sessions kept resurfacing
this as available work. The security half stays uncovered by Dependabot whenever
such an entry does eventually exist, and falls to the ecosystem's own tooling.

[Scala Steward](https://github.com/scala-steward-org/scala-steward) no longer
covers this surface *instead of* Dependabot — that framing depended on
Dependabot having nothing to offer sbt at all, and it now does. Scala Steward
is complementary at most: a separate, general-purpose version-bump mechanism
that would overlap with a configured `sbt` entry on routine updates rather
than substitute for one, and it does not close the gap Dependabot's own sbt
support explicitly leaves open — the security-update half. **Adopting either
mechanism, and any CI either needs, sits behind the same gate** — this is
recorded so the reasoning survives until the gate opens, never as a backlog item
to pick up.

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
misleading rather than merely useless.** `test` resolves to `testQuick`
semantics, so it re-runs only what it believes changed. Change one file and it
executes **only the suite that file belongs to**, then reports *"All tests
passed"* over a partial run.

**Measured in this repository, three real runs.** The two failures are not
equally visible, and the difference is the whole point:

| Run | What sbt prints | Danger |
|---|---|---|
| `testFull` | `Total number of tests run: N` · `All tests passed` | none — the reference figure |
| `test`, one spec touched | a **summary block with a smaller count**, and `All tests passed` | **this is the trap** |
| `test`, nothing changed | **no ScalaTest summary block at all** — `No tests to run for Test / testQuick`, `Passed: Total 0` | announces itself |

**The empty run is the loud one: it never prints `All tests passed`.** The
partial run does, over a subset, and that is the one that gets believed. So the
danger is not a green over zero — it is a green over a number that looks
plausible.

`scripts/check-test-run.sh` enforces this; `scripts/test-expected-total.txt`
holds the reference figure and is regenerated from a `testFull` run.

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
> implementation on 2026-07-16, against a multi-module layout, and neither has
> been reproduced here. They are recorded because both are properties of sbt
> rather than of that codebase, and because the cost of rediscovering them is a
> debugging session spent trusting a green.

**A suite that failed is never skipped.** `test` re-runs whatever failed on the
previous run, so it cannot report a green over a prior failure. The trap is
confined to the passing direction.

**`compile` is not a bare pass-through: `build.sbt` promotes a set of compiler
warning categories to hard errors**, scoped `ThisBuild`, so `compile`, `test`
and `testFull` all fail on a violation in that set, in main sources and test
sources alike. That enforcement lives in the compiler step itself; read the
enforced categories and the reasoning for each from `build.sbt` directly
rather than from a count here. **A formatter is wired and does NOT run as part
of `compile`** — `scalafmtAll` formats and `scalafmtCheckAll` reports, both
opt-in, so a green build says nothing about whether the tree is formatted. Run
the check before calling work done; the engine version is resolved from
`.scalafmt.conf`'s own `version` key rather than from the plugin, so read it
there.

**And run it through `scripts/sbt-run.sh`, because a bare `sbt scalafmtCheckAll`
can pass a file it never read.** Measured against sbt-scalafmt 2.6.2 on
2026-08-18: the plugin's check cache is keyed on a file's **last-modified time,
not its content**, so an edit that preserves mtime is invisible to a warm cache
and the task exits 0 in silence. A real violation was planted this way and
passed; the same violation with mtime free failed, which is what rules out the
file simply being clean. `cp -p`, `rsync -a`, `tar -x` and an archive restore
all preserve mtime — a `git checkout` does not, which is why ordinary git work
never shows this. The wrapper's guard 4 clears the plugin's caches before the
task and reports **97** if a check then exits 0 having reported reading nothing;
`scripts/sbt-run-proof.sh` drives both halves. **The bare command is not
equivalent, and its green is weaker than it looks.**

There is no `lint`, `typecheck` or coverage task and no CI —
`## Code style` below says what the compiler's enforced set does and does not
reach, and outside both, match the style of surrounding code by hand. Do not
invent a command — one that the build does not define will fail and read as a
broken build.

## Structure

| Path | Holds |
|---|---|
| `AGENTS.md`, `CLAUDE.md`, `README.md` | Project instructions (this file, and the one-line `@AGENTS.md` import) and the public description — different registers, not different lengths of the same thing |
| `LICENSE`, `NOTICE` | Apache-2.0 and its required attribution — see `## Boundaries` item 1 |
| `build.sbt`, `project/build.properties` | The build definition and the sbt launcher pin — see `## Stack` |
| `.sdkmanrc`, `.jvmopts`, `.gitattributes`, `.gitignore` | The JDK, JVM, line-ending and private/public-gate pins — see `## Setup` and `## Security` |
| `.claude/agents/` | This repository's own domain specialists — the directory listing is authoritative, not this row |
| `.claude/rules/` | Standards that load on their own. **Some are path-scoped and fire on a matching read; the rest carry no `paths:` and load every session** — read each file's own opening for which, since a roster here would go stale on the next rule added. `## Code style` maps the ones governing Scala |
| `.claude/protocols/` | Two kinds, neither of which auto-loads: operating discipline, and consensus domain facts — see `## Protocols` |
| `.claude/hooks/` | The hook scripts and their own tests; `settings.json` states which are actually registered |
| `.claude/settings.json` | Hook registrations and the read-deny list — see `## Boundaries` item 4 |
| `.github/copilot-instructions.md`, `.github/assets/` | Copilot's self-contained instructions, and the logo `README.md` renders |
| `scripts/` | Two kinds of thing, and they answer to different standards: **checkers** and a wrapper, most paired with a `*-proof.sh`, plus the fixtures and reference figures they run against; and **vector generators**, which produce a test resource from an external corpus rather than checking anything. See `scripts/README.md` |
| `modules/` | The layered module tree. Each module holds `src/main/scala/` and `src/test/scala/` under `org/fukuii/<module>/` — **read the directory for the current set rather than a list here** |

**`modules/` reaches a clone from the commit its first module had a file in.**
Git does not track empty directories, so the tree arrives with content rather
than ahead of it. That is a property of git rather than a status of this
repository, so it stays true on both sides of the transition and needs no
revisiting: a module directory holding no tracked file is in no clone,
whenever it is created.

## Testing

**How tests are run is `## Commands` above** — including why `testFull` is the
only figure that can be trusted for a pass/fail claim. This section owns what a
test must look like: which style each use case takes, how to write it, and the
count ratchet that governs what may land.

### Test style is assigned by use case, not by author

**The three toolchain-proof specs are retired.** They existed to show each
declared style artifact resolved and that its runner reported a real result, and
their own trigger was the first real `AnyFlatSpec` under `modules/bytes`, which
now exists alongside real `AnyPropSpec` use. Real specs prove both, and the
proofs became an artifact with nothing to do.

**One residual, stated rather than left to be discovered.** `AnyFeatureSpec`'s
runner is exercised by the first acceptance test written against it, and not
before. Resolution is still proven by every successful
build, since an unresolvable artifact fails `update` outright; what is unproven
is only that the runner reports a result, and the first acceptance test proves
that at the moment it is written, where a failure is loud rather than silent.

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
  reference-test harness could want). **It conflicts with the warning ratchet,
  and that conflict is live rather than anticipated:** `build.sbt` promotes both
  categories the table below names to hard errors, scoped so they bind test
  sources too. `.claude/protocols/warning-ratchet.md` is why the ratchet was
  configured before the code existed — a category gated first is free, and
  gated afterwards is a migration.
  Measured in the pre-rebuild tree on **the same Scala 3.3.8 and ScalaTest
  3.2.20 this repository now pins**, so the measurement applies directly rather
  than by analogy:

  | test-method body | result |
  |---|---|
  | `def x(): Unit = assert(...)` | **E175** discarded non-Unit value |
  | `def x(): Unit = { assert(...); () }` | **E176** unused value of type `Assertion` |
  | `def x(): Unit = assert(...): Unit` | compiles and runs |

  So RefSpec costs a `: Unit` ascription on every test method, or a ratchet
  exemption — **a present cost of adopting the style, not a future one to plan
  around.** If the reference-test harness later shows a real compile-time
  problem, revisit with that measurement in hand — the ratchet is the thing to
  weigh it against, and generated code can emit the ascription for free.

### Writing a test — the standing rules

**The count is deliberately not in this heading.** It read "three" while listing
four, because a rule was added and the number was not. A heading that counts its
own contents rots on the next edit.

**No `Thread.sleep`.** Wait on the condition, not on the clock — an
`eventually`-style retry or a probe expectation. A sleep is either too short and
flaky or too long and slow, and it is usually both on different machines.

**No skipped test without a stated gate condition.** A one-line reason naming
what would re-enable it. A test skipped with no condition is a deleted test that
still shows up in the count.

**One behavior per test, not a scenario script.** A test that walks through six
steps fails at step four and tells you almost nothing; six tests tell you which
one broke.

**And the compiler enforces it, in every style — this is not only advice.**
ScalaTest's `assert` returns `Assertion`, so under the warning ratchet's
`-Wnonunit-statement` every assert that is *not* the last expression in its
block is a discarded non-Unit value: **E176, a hard error.** Measured in this
repository, 2026-08-08, writing the first `bytes` specs — a two-assert
`AnyFlatSpec` body does not compile. So a multi-assert test body is a build
failure here, not a review comment, and the remedy is the rule above rather than
an ascription.

**This is distinct from the RefSpec cost recorded below, and the two are easy to
conflate.** RefSpec's cost lands on *every* test method including a
single-assertion one, because the method's declared `Unit` return discards the
`Assertion` (E175). The rule here is narrower and applies to the styles this
project does use: only the non-final asserts in a block. **Both come from the
same ratchet category; neither implies the other.**

Where several facts genuinely belong to one subject, they are separate tests
sharing a helper, or a table-driven `AnyPropSpec` — which is what the
vector-table row of the assignment above is for.

**Deterministic across machines.** No dependence on wall-clock time, filesystem
ordering, locale, or an available port.

**A recursive grep from the repository root does NOT see this project's own records.**
`grep -r` skips gitignored paths here, and the roadmap, the section plans and the session
records all live under a gitignored directory. So a sweep for a stale claim, a wrong value
or an old phrasing will report zero while the claim sits in the very files that govern the
work. **Name the files directly, or use `git grep` for tracked text and a direct path list
for the rest** — and calibrate any sweep against a token you know is present, because the
failure returns a clean zero rather than an error.

**A `val` or `def` declared BELOW a test registration is a HARD ERROR, not a style
preference, and its diagnostic names neither the field nor the ordering.**
Scala 3's initialization checker treats a field referenced by an already-registered
test body as read-before-init, and the message points at the **first test in the
class** rather than at the field. So a spec whose fixtures sit at the bottom fails
with a diagnostic that sends you looking at a test that is fine. **Put fixtures,
helper `def`s and table `val`s above the first test** — it reaches all three, not
only fixtures.

**Recognize it by its wording, because nothing in it suggests ordering:** roughly
forty lines about a *"non-transitively initialized (Warm) object of type (anonymous
class `org.scalatest.verbs.StringVerbStringInvocation`)"*. `Warm` or `Hot` beside a
ScalaTest verb class means this and nothing else. Observed twice, 2026-08-19, by two
different agents building specs; it cost each of them a build cycle, and the second
one only recognized it because the first had written it down.

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
much ScalaTest documentation still shows the old form. This was confirmed
against the pinned ScalaTest 3.2.20 by a toolchain-proof spec written in exactly
this form, which compiled and ran — **but that spec has since been retired, so
nothing in this repository exercises the syntax today and the confirmation is a
record rather than a live check.** The first acceptance test restores it: written
in the form below, it either compiles or names the problem immediately.

```scala
class ThingSpec extends AnyFeatureSpec with GivenWhenThen:
  Feature("..."):
    Scenario("..."):
      Given("...")
      When("...")
      Then("...")
```

## Code style

**Some of this is enforced by the compiler; most of it is not.** `build.sbt`
promotes a set of warning categories to hard errors, so a violation in that
set fails `compile` outright rather than waiting on review — see `## Commands`
for how that reaches `test` and `testFull` too, and `build.sbt` itself for
which categories and why. That set is narrower than it looks:
`.claude/rules/scala3-style.md` § "Build configuration" names further
prohibitions that no compiler flag in `build.sbt` reaches — those still need a
dedicated lint plugin this repository does not have. **A formatter is wired and
reaches none of them** — it decides layout, not which constructs are permitted,
so the two gaps are unrelated and neither closes the other. There is no CI.
Everything neither the compiler nor the formatter catches is checked by review,
so it binds by being read.

**The rules carry the detail. They are the authority; this section is the map.**

| Rule | Governs | Loads |
|---|---|---|
| `.claude/rules/scala3-style.md` | Scala 3 idiom, design rules, `given`/`using` semantics, logging conventions, and the lint prohibitions to switch on when a linter first lands | on a `.scala` read |
| `.claude/rules/nomenclature.md` | The vocabulary a name is drawn from — identifiers, types, and prose | on a `.scala` read |
| `.claude/rules/comment-content.md` | What a comment is for, and what must never appear in one | on a `.scala` read |
| `.claude/rules/reference-first.md` | **What must be consulted before a structural recommendation is formed** — the production clients, and how to survey them without lying to yourself | **every session** |

**A `paths:`-scoped rule fires on the Read TOOL's path match, and not on `cat`.** Opening a
`.scala` file through a shell command — `cat`, `sed -n`, `tail -n +1`, `head` — loads the
file's contents and loads **none of the three rules above**. So an agent working under an
operating mode that prefers shell reads writes Scala with `nomenclature.md`,
`scala3-style.md` and `comment-content.md` silently absent, and nothing reports their
absence. **Read a `.scala` file with the Read tool when the rules matter, or open the three
explicitly before writing Scala.** Observed 2026-08-19 by an agent that had read the whole
EVM module through the shell and had to fetch all three afterwards.

**The `Loads` column is the point of the table, not decoration.** The first
three fire when Scala is opened and therefore **not during design discussion**,
which is exactly when structure, placement and naming are decided. That gap is
why the fourth is unscoped, and why the two rules below are stated here rather
than behind a pointer: they must reach you before a file exists.

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

`.claude/protocols/` holds two kinds of file. **Operating discipline** — each one
a moment, an actor, and a trigger. And **consensus domain facts**, which are not
discipline at all: they are what a consensus task must know, held here because
this directory is what a charter body can point at. **A fact file belongs here,
and the majority of the directory is now fact files** — do not read the
discipline half as a membership test. **The directory listing is authoritative,
not this table.**

**These do NOT load automatically. `.claude/rules/` does; this directory does
not**, so nothing puts a protocol in front of you at the moment it applies. Read
the one that matches before you act, not the ones you happen to remember.

| Protocol | The moment it governs |
|---|---|
| `dead-code-review.md` | Before deleting code that looks unused |
| `warning-ratchet.md` | Configuring a lint or warning category — the window closes once code exists |
| `scope-boundary.md` | A scoped task that appears to need work outside its scope |
| `consensus-pow.md` | Before acting on a proof-of-work consensus change — that family's domain facts |
| `consensus-pos.md` | Before acting on a proof-of-stake consensus change — that family's domain facts |
| `consensus-clique.md` | Before acting on anything Clique-shaped — that mechanism's surveyed facts |
| `consensus-aura.md` | Before acting on anything AuRa-shaped — that mechanism's surveyed facts |
| `consensus-qbft.md` | Before acting on anything QBFT-shaped — read with `consensus-ibft2.md` |
| `consensus-ibft2.md` | Before acting on anything IBFT2-shaped — read with `consensus-qbft.md` |

**Where a protocol carries a fact you cannot afford to miss, that fact is also
in a rule that loads on its own** — so the protocol holds the procedure and the
rule holds the trap. `dead-code-review.md` is the worked case: its hazard, that a
`given` can have zero textual references and still be live, is stated in
`.claude/rules/evidence-and-citation.md` §3, which loads every session.

**The consensus protocols are instructed by a charter body instead, and that is a
second mechanism rather than a gap in the first.** Their facts are needed only by
a consensus task, every consensus task routes to `forge`, and a charter body
loads when its agent is dispatched — so `forge`'s charter is what instructs the
read, and it is written to make that instruction unmissable. Putting those facts
in a rule would charge every session that never touches consensus for them.
**The cost of the mechanism is that it depends on one document**: if `forge`'s
charter stops naming a protocol, that protocol goes dark with nothing reporting
it. **That cost scales with the count**, and the count is no longer two.

**Two kinds sit among them, and only one is keyed to a network fukuii runs.** A
**family** protocol covers a family of networks this project runs. A
**mechanism** protocol covers one consensus mechanism, written from a survey of
production clients rather than from anything on the roadmap — **it commits this
project to implementing nothing**, and must not be read as scheduling work. Each
file says which it is, and states its own evidence weight, at its own head.

**They also differ in how far their facts can be trusted, which is why each
carries a header saying so.** The two family protocols inherited their domain
facts from this project's prior implementation and open with a `currency:` header
declaring every one unverified. The mechanism protocols open with a
`provenance:` header instead: their facts came from a dedicated conformance pass
and were re-verified with calibrated controls. **Do not carry either header's
wording onto the other kind** — the whole point of the difference is that a
reader can tell them apart at a glance.

### Adding a mechanism protocol takes three edits, and `forge` may perform none of them

**The files assert well that the set is open. This is the part that is not
self-evident from reading them**, and it is live rather than hypothetical: a
mechanism with no protocol here is one directory away in the reference corpus,
and whoever surveys it arrives at this procedure. **Which mechanisms are
unsurveyed is a reading of the corpus against the `provenance:` headers in
`.claude/protocols/`, not a list to keep here** — a named directory with a
present-tense "is unsurveyed" beside it goes false the moment someone surveys
it, and nothing re-reads this page when they do.

**The next mechanism** needs **all three**, and the first alone accomplishes
nothing. **No count is written here, deliberately** — the previous wording said
*a seventh*, which counted the six protocol files rather than the mechanisms
among them, and the roster carries the distinction on its face: a mechanism
protocol opens with a `provenance:` header, a family protocol with a
`currency:` one. **Derive it from the headers if you need it**, the same way
`.claude/agents/forge.md` stopped reporting how far its own list had grown:

1. **The protocol file** under `.claude/protocols/`, written to the shape the
   existing mechanism protocols use — its own `provenance:` header, its own
   evidence weight, its own statement that the set stays open. **Never folded
   into an existing file**: the fold hides the divergence that justified the
   survey.
2. **A line in `.claude/agents/forge.md` § "Read the protocol before you act"**,
   because a charter body is the only thing that delivers this directory. A file
   nothing names goes dark with nothing reporting it.
3. **A row in the table above**, so a reader who never dispatches `forge` can
   still see it exists.

**The actor is not `forge`.** Its charter forbids it from writing a missing
protocol — so **the one agent guaranteed to notice the gap is the one that may
not close it**, and that is deliberate rather than an oversight. `forge` reports
the gap as a finding; **the driving thread commissions the work and owns all
three edits landing together.** Two of three is the failure mode this arrangement
produces, and it is silent.

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
   Everything else under `.claude/` — every file and subdirectory the deny-list
   does not name, whatever it is called and whenever it is added — is
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
   unless you have checked `.claude/settings.json` and found it covered.** When
   key material is generated or read, report **what** it is and **where** it was
   stored — path and permissions — never the value itself.

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
