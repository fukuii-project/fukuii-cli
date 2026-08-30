# `scripts/` — repository test infrastructure

**currency:** The conformance claim — that this layout and these contracts match the house collector
standard — was checked 2026-08-06. **That date bounds the conformance claim only, not the whole
document**; sections describing this repository's own decisions are added as those decisions are made
and carry no external check to go stale against. The exit-code contract and the read-only rule come
from that standard; the two-gate threshold and the root-resolution contract are stated here because
they are decided before any script exists.

## Two homes for a script, and a third that is not one

**`scripts/` — repository test infrastructure.** Committed, run by hand or by a
reviewer, and calibrated. A file lands here when it has **already proven
reusable**, not when it is merely scriptable.

**Three kinds of file live here and they are held to different standards.** A
**checker** asserts a property of this repository, and everything under "Why
every checker ships with a proof" binds it. A **vector generator** asserts
nothing: it reads an external corpus and writes a test resource, and its output
is checked by the suite that consumes it rather than by a proof of its own.
Reading the second kind as the first is how a generator acquires a demand for a
known-bad fixture it has no way to satisfy.

A **runner** is the third and does neither: it invokes the build and shapes how
its output is observed. `sbt-run.sh` is the one that lives here.

**A runner earns a place in this directory only if its REASON TO EXIST is a
property of this repository.** That test separates two things a single grep
cannot: `sbt-run.sh` cites an agent incident as the origin of its file-logging
rule, and is still repository infrastructure, because logging build output rather
than streaming it -- and refusing to report a success sbt did not earn -- is
useful to anyone who builds this project. A runner written because *one
particular caller* cannot wait long enough is not: nothing about this repository
produces that limit, a reader who clones does not have it, and shipping the
script would put text in a public tree that its reader cannot act on.

**That distinction created a third home**, below.

**`.local/scripts/` — maintained, and machine-local.** Gitignored. Tooling that
has earned upkeep -- a proof of its own, a documented contract -- but whose
reason to exist belongs to the environment running the build rather than to the
build. A detaching runner written around one caller's time limit is the worked
case: it is not a one-off, and it is not repository infrastructure either.

**This is a real third answer and not a softer `scripts/`.** The test is not
quality. A file here can be better made than one in `scripts/` and still belong
here, because what decides placement is whose problem it solves.

**`.local/scratch/<slug>.sh` — a one-off.** Gitignored, written for one
investigation, deleted or forgotten afterwards. This is the correct home for a
script that clears the "worth scripting" bar and fails the "worth maintaining"
one, and it is not a lesser tier of `scripts/` — it is a different answer.

**Two gates, not one, and they compose.** Call count answers *is scripting this
worth it at all* — three or more mechanical steps. Recurrence answers *has it
earned a place in shared, maintained tooling*. A one-off with eight mechanical
steps clears the first and fails the second, and belongs in `.local/scratch/`.

**Every script resolves its own root from its own location:**

```bash
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
```

Never a hardcoded path, a username, or a clone location. This repository is
public: a machine-local path in a committed script breaks for every clone,
including its author's next machine. The depth is part of the contract — moving
a script one directory deeper silently changes what `REPO` means, and no search
for a path finds it, because there is no path to find.

**A checker takes its target as a parameter.** That is what lets its proof drive
it against a throwaway tree instead of this one, and it is why no proof here has
to mutate the artifact it certifies.

## Why every checker ships with a proof

**A check that has only ever returned clean is indistinguishable from one that
cannot fail** — and it is worse than no check, because it converts *"unchecked"*
into *"checked and passed"* in the reader's model. Every artifact that passes
through inherits that false assurance.

That is measured, not asserted: the tooling layer this repository's framework was
drawn from scored **0 for 73** on positive controls — 73 checks never shown able
to fire, all reporting green.

So a proof is not optional decoration. It must:

1. **Fail on a known-bad fixture**, and say which cases fired — not merely that
   something did. An arm that passes for an incidental reason is a placebo for
   the class it claims to cover.
2. **Pass on a known-good fixture.**
3. **Report "could not run" distinctly from "clean."** An empty input set and a
   clean input set must not produce the same exit code.
4. **Catch a plausible seeded regression** — the kind of edit someone would
   actually make while "tightening" the check, not a total ablation.
5. **Touch nothing in this repository.** Arms run against throwaway trees.

**Fixtures that would otherwise be discovered are stored under a neutral
extension** — a real `.scala` fixture is found by `git ls-files` and makes this
repository fail its own gate. The proof renames on copy, so discovery still runs
against a realistic name.

**A proof's reference must be a tracked file at a stable path**, never
`git show HEAD:` — a reference read from the commit under test certifies that
the file matches itself.

## Adding one

Confirm both gates. Follow the house collector standard for exit codes and the
read-only contract. Ship a proof with a known-bad and a known-good fixture, seed
a plausible regression and watch the proof catch it, and run `shellcheck`.

**`shfmt` is a reporter here, never a writer.** It flags every script in this
directory, as it does the wider tooling corpus, because its default style is not
the one in use. Formatting is a policy decision, not a lint fix — do not run it
with `-w`.
