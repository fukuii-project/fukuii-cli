# Read the field before recommending a structure

**No `paths:` frontmatter, deliberately, and the reason is the whole point of the
rule.** A path-scoped rule fires when a matching file is *read*. The failures
below happened while reasoning in prose, with no file open at all — a
recommendation about shape, written and delivered before anything was
consulted. That is decision-shaped, not file-shaped, so scoping this would make
it dead at exactly the moment it is needed.

`.claude/rules/evidence-and-citation.md` gives the same reasoning for its own
lack of frontmatter, and the two rules are siblings: that one governs how a
claim is *supported*, this one governs what must be *consulted before the claim
is formed*.

**The built-in `Explore` and `Plan` agents skip the rules hierarchy entirely**
and will not load this. `Plan` is the one that most needs it, since designing is
precisely when it binds. Restate it in their prompt.

---

## The rule

**A decision about structure, shape, placement or naming begins by reading the
production clients — before a recommendation is written, not after one is
challenged.**

`reference-corpus.md` says which repositories exist and what each is
authoritative for. **Derive the set from that file rather than recalling it**,
and read its framework-structure carve-out in full before leaning on any client
row — it bounds this rule in both directions and is not restated here.

**What counts as a structural decision**, since the trigger is the part that
gets missed:

- Where a type, module or codec lives, and what it is called.
- Whether something is one type or several, a sum or a record, a field or a
  parameter.
- Whether a seam is pluggable, and where the plug goes.
- Whether a shape should be supported at all.

**The last one is where this rule was written from.** A recommendation to *not*
support something is a structural decision exactly as much as a recommendation
to build it, and it is the one that feels like it needs no evidence.

## Why the field, and not just the specification

The specification says what the bytes are. **The clients say what goes wrong**,
and that is the half you cannot derive.

A specification will not tell you that one implementation has to clear a field
by hand on both decode paths because a stale value changes the block hash. It
will not tell you that two implementations chose different representations of
the same idea and both left a comment about the hazard. Those comments are the
most valuable thing in the corpus, because they are somebody's incident report
written at the point of the fix.

So read the implementation **and its comments and mitigations**, not only its
type declarations. A shape copied without its warnings is the shape plus a bug
nobody has hit yet.

## What a survey has to produce

Not "I looked." A survey answers, per client:

1. **Does it implement the thing at all?** An absence is a finding and is often
   the majority answer.
2. **If so, what is the mechanism**, in its own vocabulary?
3. **Where do the implementers disagree?** Agreement on semantics with
   disagreement on representation is the most common and most useful result: it
   means the semantics are settled and the representation is yours to choose.

**Then say which way the evidence points, and say it even when it reverses your
prior recommendation.** The survey is worth nothing if its conclusion was fixed
before it ran.

## Deriving beats recalling, and both beat a count

Never state how many clients do something from memory, and never write a roster
here. This repository has already paid for a hardcoded count going stale, which
is why `AGENTS.md` tells a reader to derive the built module set with
`git ls-files` rather than listing one. The same applies to the corpus.

## Calibrate the instrument, or the survey lies quietly

**A survey is a search, so `.claude/rules/evidence-and-citation.md` §3 governs
it**, and the absence half is the dangerous one: a client that implements the
thing, reported as not implementing it, is a false negative that reads exactly
like a real result.

**Worked instance, from the pass that produced this rule.** A sweep for
consensus engines used `-iname "aura"`, which matches a path component named
exactly that. One client's directory is `Nethermind.Consensus.AuRa`, so the
sweep reported **zero** for a client already known to implement it. The
instrument was wrong, the output was plausible, and nothing about the result
looked unchecked.

So: **run the sweep against a client you already know is positive, and one you
know is negative, before believing any row of it.** A survey that cannot report
a known-positive is not measuring what its column header says.

## What this does not license

**It is not a mandate to copy.** The clients are evidence about what is
possible, what is conventional, and what has hurt. Departing from all of them is
legitimate and this project has done it deliberately more than once — the
requirement is that the departure be *stated as one*, with its reason and, where
the design admits it, a trigger that would reverse it.

**Nor does it move the authority line for values**, which the carve-out cited
above already fixes and this rule leaves exactly where it was.
