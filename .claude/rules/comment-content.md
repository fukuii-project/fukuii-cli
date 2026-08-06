---
paths:
  - "**/*.scala"
---

# Comment content: what a `//` is for, and what belongs in a commit message

**currency:** the comment categories and the never-in-code list are project
policy and do not expire on a vendor's schedule. **One item below is inherited
from another project and unverified — the four categories' attribution — and it
is marked in place.** The one dated dependency is `AGENTS.md` § Branching, which
decides whether the deferred section below is live. Checked 2026-08-06: the
pull-request workflow was not in effect.

**Path-scoped, so it loads when Claude opens a Scala file rather than into every
session.** That is the right trigger for this rule: a comment is written while
editing source, so the rule arrives exactly when it binds. The built-in
`Explore` and `Plan` agents skip the rules hierarchy entirely and will not load
it; restate anything load-bearing in their prompt. A path-scoped rule is also
**not re-injected after `/compact`** — it reloads the next time a matching file
is read.

**This governs comment *content*. It does not govern language idiom** — see
`.claude/rules/scala3-style.md` — **or the vocabulary a name is chosen from** —
see `.claude/rules/nomenclature.md`, which binds a comment's wording too.

**It governs source code. Build definitions are a different register, and this
rule does not reach them.** `build.sbt` and the files under `project/` exist to
record *why a build choice was made* — which version, which artifact, what was
measured, what breaks if it changes — and that rationale is the file's main
content rather than an exception to a no-comment default. Do not strip it as
narration, and do not carry its discursive register into `src/`. Where a build
file needs a rule of its own, it is not this one.

---

## Default: no comment

Clear names and small, focused functions carry the meaning. Most new code,
including code written by an agent, should carry no new comments at all.

Before writing one, ask whether renaming, extracting a helper, or restructuring
would remove the need. Usually it does, and the restructure is the better
change.

## The four things that earn one

1. **A workaround** for a defect in a dependency, the runtime, or other code.
2. **A non-obvious invariant or constraint the types do not enforce.**
3. **A surprising edge case** a reader would otherwise miss.
4. **A performance-sensitive choice** where the obvious implementation is wrong.

One sentence, rarely two. No bulleted sub-lists inside `//`. If a reader could
delete the comment and lose nothing, delete it.

> These four categories are inherited from another Ethereum client's own comment
> rules by way of this project's prior implementation, and the attribution has
> not been re-verified against that project's current text. They are stated here
> as this project's rule and stand on their own; the provenance is recorded, not
> relied on.

## Scaladoc is a different genre, and is not discouraged

The four categories govern **inline `//` explaining a decision at a specific
line**. They do not govern `/** ... */` documenting a public class, object, or
method contract. That is ordinary Scala API documentation and this rule does not
discourage it.

Judge scaladoc by scaladoc conventions — an accurate contract, useful `@param`
and cross-references, and a stated reason where a boundary or dependency choice
is non-obvious. **The one-sentence limit above does not apply to it**; a
class-level contract doc legitimately runs several lines.

The rule that does reach scaladoc is the vocabulary one. A shared abstraction
documented in one network family's fork vocabulary carries the same conflation
risk as naming it that way — `.claude/rules/nomenclature.md` states it.

## Never in code — this goes in the commit message

- **Scope, limitation, or "honest" narration** — "forward-only", "safety net",
  "cannot repair X", "NOTE: this only…".
- **Incident or reproduction narration** — dates, branch names, "deployed via X,
  called N blocks later at M", post-mortem storytelling.
- **Restating the code** — `// increment counter` over the line that increments
  the counter.
- **The same rationale repeated at several sites.** State the *why* once, where
  it belongs — on the field, type, or function it is about — and use a terse
  pointer elsewhere.

### Rebuild-provenance narration, which is this repository's live risk

**This project has prior implementations, and their vocabulary is all over its
internal working documents. None of it ships in source.**

Never write "the pre-rebuild code", "the old code", "this replaces X", "we
pivoted from", "eliminating tech debt", "modernization sprint", or any
how-we-got-here comparison. A comment describes *this* code and stands on its
own.

This is the same discipline `.claude/rules/evidence-and-citation.md` §4 states
for tracked prose, arriving at source comments: **a public artifact does not
carry internal development vocabulary.** A reader who clones this repository has
no access to that history and no way to interpret a reference to it.

Where a comment genuinely must cite a prior implementation, cite it the way
`.claude/rules/evidence-and-citation.md` §1 requires — by an immutable ref, a
version or a commit, never by a branch name, and never by an internal shorthand.
And note that a prior implementation is never a correctness oracle, so a comment
citing one is recording where something came from, never why it is right.

## Test docstrings

Slightly more latitude: explaining a non-obvious scenario a test pins is
legitimate, because the scenario is the thing under test and is often not
recoverable from the assertions. The same bans apply — no incident, scope, or
task narration.

## Where an enforcement mechanism mandates a comment form

A gate that requires a specific annotated comment before it will accept a
suppression creates a sanctioned comment genre, and this rule does not override
it. The standard that defines the gate defines the format, and a future
enforcement pass must not flag its own required form as a violation.

No such gate is configured in this repository today. Written as a standing
carve-out rather than a list, because a list of exempt forms goes stale the
moment a gate is added or removed.

---

## Deferred: inline `#NNNN` issue and pull-request citations

**Not in force. Trigger: the pull-request workflow activates.**

The prior implementation ratified a deliberate divergence from the stricter
convention above: a terse `#NNNN` marker naming the design constraint a change
must preserve was treated as a sanctioned "why" citation rather than as task
narration, on the grounds that it states what must remain true and where that
was decided in one line.

**That has no referent here.** `AGENTS.md` § Branching states plainly that this
is a single-developer project, that the branch-and-pull-request policy is **not**
in effect, and that the operator declares when it activates. A citation form for
pull requests that do not exist would be a rule nobody can follow correctly.

When the operator activates that workflow, re-derive this section rather than
restoring it verbatim — the divergence was argued against one specific upstream
convention and rested on an established body of existing call sites, neither of
which is the situation here.

## What is not in force from the prior implementation

Two comment genres it sanctioned are absent deliberately, not by oversight. Both
existed to mark intermediate states of a Scala 2 to Scala 3 migration — an
annotation for a non-obvious compile-error fix, and an annotation marking a
member deliberately left as `implicit val` because it must stay overridable.

This project is Scala 3 from the first line and produces neither state. The
second one's underlying language fact is real and is stated where it belongs, as
a design constraint rather than a migration exception, in
`.claude/rules/scala3-style.md`.
