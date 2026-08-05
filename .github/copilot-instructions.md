# GitHub Copilot — repository instructions: Fukuii

<!--
  SELF-CONTAINED BY CHOICE. Do not thin this into a pointer at AGENTS.md.

  This repository is public, so its github.com surfaces are in play for anyone
  browsing it. github.com Chat reads this file and does NOT read AGENTS.md; the
  same is true of several other Chat and code-review surfaces. On those, this
  file is the only instruction the model sees, so a thin delta pointing
  elsewhere would leave it with nothing.

  The per-surface support matrix deliberately is NOT reproduced here. It changes
  on roughly a monthly cadence, and a copy pasted into a repo goes stale without
  anyone noticing. Read it at the source before revisiting this choice:
  https://docs.github.com/en/copilot/reference/custom-instructions-support

  The cost of this choice is duplication with AGENTS.md, and it is a cost that
  has to be actively paid: when either file changes, update both. Where a
  surface reads both, both are supplied to the model and neither is dropped, so
  the actionable rule is that THE TWO MUST NOT CONTRADICT EACH OTHER.

  DEFAULT WIRING — the content sections below are deliberately unfilled. Fill
  each from what this repository actually contains, never from what a comparable
  project usually contains.
-->

## Project

<!-- What this project is, in two or three sentences. Leave unwritten until
     there is a settled answer; a description invented here is a public claim. -->

## Stack

<!-- Language, runtime and build-tool versions, read from this repo's own
     manifest and lockfile. Record the major series, not an exact patch. -->

| | Version | Declared in |
|---|---|---|
| Scala | **3.3.x LTS** | `build.sbt` |
| JDK | **25 LTS**, Eclipse Temurin | `.sdkmanrc` |
| sbt | **2.0.x** | `project/build.properties` |
| ScalaTest | **3.2.x**, `Test` scope only | `build.sbt` |

**Scala is on the LTS line, and that is line membership rather than a version
preference.** Scala Next is a different line this project does not run, not a
higher number that was skipped.

**The JDK distribution is named deliberately.** End-of-life dates differ between
distributions by years, so a JDK version without a distribution does not say
what is actually supported.

**No compile-scope library dependency is declared, and that is deliberate.**
Each one is added when a real need for it arises. A dependency with no present
need is not an entry — do not add one because another project has it. There is
no dependency-update configuration yet.

## Commands

<!-- Copy the real task names verbatim from this repo's build definition. Every
     command listed must exist. State absent tooling as absent — "there is no
     lint or test task here" tells the model to match style by hand rather than
     trust a gate that does not exist. -->

```
sdk env            apply the JDK this repo declares (once, per shell)
sbt compile        compile
sbt test           run tests — may report success having run none or only
                   some of them, see "Testing"
sbt testFull       run tests uncached; the one to trust for a pass/fail claim
```

**Run `sdk env` first, or you are not building against the declared toolchain.**
`.sdkmanrc` names the JDK and its distribution; without applying it, sbt runs
under whatever JDK the shell happens to provide.

**These are sbt's own tasks; the build defines none of its own.**

**There is no `lint`, `format`, `typecheck` or coverage task, and no CI.** Match
the style of surrounding code by hand rather than relying on a gate that does
not exist. Do not invent a command — one the build does not define will fail and
read as a broken build.

## Structure

<!-- The actual directory tree, one line of purpose per entry. -->

## Testing

<!-- How tests are run and what must pass before a change lands. -->

**Use `sbt testFull`, not `sbt test`, before treating a run as evidence anything
passed.** sbt 2 caches the test result machine-wide, and that cache survives
both `clean` and copying the project to a new directory. This is documented,
intended behavior rather than a defect; `testFull` is the uncached equivalent.

**The cache is per-suite and keyed on inputs, and that is what makes `test`
misleading rather than merely useless.** With nothing changed it executes
nothing and reports success. Change one file and it executes **only the suite
that file belongs to**, then reports *"All tests passed"* over a partial run.
The loud failure is the zero; the quiet one is the partial, and the partial is
the one that gets believed.

**So check the executed test count against the EXPECTED TOTAL** — not the exit
code, and not merely against zero. Count tests, not files: a suite is a class, a
test is one assertion block inside it, and one class commonly carries several.

**Test style is assigned by use case, not by author** — `AnyFlatSpec` for unit
and integration, `AnyPropSpec` for property checks and vector tables,
`AnyFeatureSpec` for acceptance. `build.sbt` names those three style artifacts
individually and never the `scalatest` aggregate, so an unlisted style does not
resolve and using one is a compile error. **Assertions are `assert(cond, clue)`,
never matchers.** A bare `assert` failure prints nothing actionable.

## Code style

<!-- Indentation, line endings, naming and import conventions — and for each,
     whether a formatter or linter enforces it or it is convention only. -->

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
  `.env.example` is deliberately visible and must contain placeholders only.
- **Report a suspected exposure; do not quietly clean it up.** Deleting a
  committed secret leaves it retrievable from history, so the response is
  rotation, not removal, and that is a human decision.

## Do not touch without asking

1. **`LICENSE` is Apache-2.0 by deliberate choice.** Never change, replace or
   remove it, and never propose a different licence.
2. **Community-health files are inherited, not missing.** The `fukuii-project`
   organization supplies `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`
   and the issue and pull-request templates to every repository lacking its own.
   Do not add local copies.
3. **`.claude/` is tracked by default — a deny-list, not an allow-list.** Only
   specific machine-local and agent-written paths are excluded (see
   `.gitignore`'s Claude Code block — `settings.local.json`, `worktrees/`,
   `agent-memory-local/`, and similar); `hooks/`, `rules/`, and `settings.json`
   are ordinary tracked content. `.claude/settings.json` denies reads of exactly
   seven patterns — `.env`, `.env.*`, `secrets/**`, `*.pem`, `*.key`,
   `*.keystore`, `*.p12` — and is **not** a general "key material is protected"
   guarantee. It does not cover `UTC--*` keystore files, `wallet.json`,
   `mnemonic.txt`, `*.jks`, `*.pfx`, `id_rsa`/`id_ecdsa`/`id_ed25519`, `.netrc`,
   `.git-credentials`, `credentials.json`, `jwt.hex`, `jwtsecret` or
   `*.nodekey`, all of which `.gitignore` treats as key material. The patterns
   are also `./`-anchored, so they resolve against the current directory rather
   than the project root, and they do not reach a subprocess that opens a file
   itself. Changing either is a security decision, not a convenience fix.

## Response style

- No pleasantries. Code first, explanation only if asked.
- Concise bullets over paragraphs; do not repeat the prompt back.
