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

No manifest or build-tool configuration exists on this branch yet — nothing to
record, including no dependency-update configuration, until one lands.

## Commands

<!-- Copy the real task names verbatim from this repo's build definition. Every
     command listed must exist. State absent tooling as absent — "there is no
     lint or test task here" tells the model to match style by hand rather than
     trust a gate that does not exist. -->

No task runner or build definition exists on this branch — there is no
`build`, `test`, `lint`, or any other command to run yet. Do not invent one.

## Structure

<!-- The actual directory tree, one line of purpose per entry. -->

## Testing

<!-- How tests are run and what must pass before a change lands. -->

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
