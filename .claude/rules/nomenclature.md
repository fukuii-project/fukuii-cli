---
paths:
  - "**/*.scala"
---

# Nomenclature: which vocabulary a name may be drawn from

**currency:** the two-tier rule and the anti-conflation discipline are project
policy and do not expire. **Every ecosystem identifier named below — chain IDs,
proposal numbers, fork names, engine names — is inherited from this project's
prior implementation and has NOT been re-verified against its registry.** They
appear as illustrations of where to look, never as values to copy. Checked
2026-08-06: no verification was performed in this pass.

**Path-scoped, so it loads when Claude opens a Scala file.** Note the limitation
squarely: **this rule's highest value is before a symbol is written at all**, and
naming happens in design discussion and commit messages as much as in source, so
the trigger is narrower than the rule's subject. It is also the rule the built-in
`Explore` and `Plan` agents most need and will never load, since they skip the
rules hierarchy entirely. Restate it in their prompt, and reach for it
deliberately when naming something before any file exists to open. A path-scoped
rule is also **not re-injected after `/compact`** — it reloads the next time a
matching file is read.

**This governs which vocabulary a name may be drawn from.** The language idiom a
symbol is written in is `.claude/rules/scala3-style.md`; what a `//` explaining
it may say is `.claude/rules/comment-content.md` — though this rule binds a
comment's wording too, since prose leaks vocabulary as readily as an identifier
does.

---

## Consume the ecosystem's vocabulary; do not invent a parallel one

Align names with the established EVM ecosystem — the reference clients and the
improvement-proposal specifications. Reusing an identifier the ecosystem already
has is free interoperability with every tool, document, and engineer that
already speaks it. Inventing a local synonym for something that already has a
name is cost with no benefit.

If an ecosystem-standard identifier exists for the thing you are naming, use it.
Do not paraphrase it, abbreviate it, or give it a project-local alias.

**A prior implementation is not an authority for a name any more than it is for
a value.** `.claude/rules/evidence-and-citation.md` §4 states that rule on the
**value** axis — a prior implementation is never a correctness oracle. This is
the same rule on the **name** axis, and it is stated here rather than assumed,
because §4's own wording allows a prior implementation to be *"a structural
guide, telling you where something lived and what shape it was"* — and a name
reads far more like *shape* than like a value. A reader reasoning from §4 alone
could conclude that inheriting a name is exactly what it licenses.

So: **do not carry a prior symbol name forward because the shape matches.** That
imports an un-reviewed naming choice on the same "it is already there" basis §4
forbids for values. The fuller reasoning behind the oracle rule lives in §4 and
is not repeated here.

## Identity comes from a registry — look it up, do not recall it

| Concept | Where its canonical identifier comes from |
|---|---|
| A network | Its **chain ID**, the ecosystem-wide unique network identifier |
| A feature or behavior change | Its **improvement-proposal number**, in the relevant family's series |
| A consensus engine | The **specification that defines it** |

**Read the value from the registry at the moment you use it.** A chain ID or a
proposal number recalled from memory, or copied out of a document like this one,
is exactly the laundering this project's evidence discipline exists to stop: the
number looks authoritative in its new home and nothing records that it was never
checked. `.claude/rules/evidence-and-citation.md` §1 states the general form —
a version claim is answered by the registry, never by a clone or a cached copy.

The prior implementation modeled the proposal-number concept as a sum type over
the per-family series. **No such type exists in this repository**, and the shape
is recorded as structural precedent only, never as a specification.

## The two-tier vocabulary

Every name falls into one of two tiers. Getting the distinction right is the
whole of this rule.

**Tier 1 — neutral, conceptual, ecosystem-wide.** Terms describing a *concept*
rather than one network's history: proof-of-work and proof-of-stake, chain ID,
proposal numbers, gas, base fee, opcode, precompile, total difficulty. **Use
these at the shared or framework level** — anything that spans, or could span,
more than one network family.

**Tier 2 — one network family's own fork and event names.** Names belonging to a
single network's actual history: a family's individual hard-fork names, and
event names like "the Merge". These are **family-local instance labels only**.
They name that family's release of a capability, never the capability itself.

**The rule:** at the shared level, name a capability by its neutral proposal
number, never by one network's fork name for it, and never by one network's
event name for it. Each family maps the proposal onto its own release, and that
mapping belongs in the family's own fork-dispatch configuration — not in the
shared abstraction's name.

### Why this matters more here than in a single-network client

This project is deliberately multi-network. A network-event name used as though
it were generic is the same failure as a shared mutable definition: it invites
the next reader, or the next agent, to treat a one-family concept as applying
everywhere, and to edit it accordingly.

**The incident, from this project's prior implementation.** An opcode list for
one network family was correctly named with that family's prefix. The *other*
family's dispatch path then reused the **unprefixed** form of that same fork
name for its own, different opcode list — and defined a third name as a bare
alias of it rather than as an independent definition. A future proposal landing
in the first family's fork would have silently mutated the second family's
opcode set through the shared name.

It was repaired by giving each family independently-named definitions and
deleting the unprefixed names. **The vocabulary rule is what should have stopped
the name being chosen in the first place**, which is why this rule exists at the
vocabulary layer rather than as a code review check.

> **Inherited and unverified.** The symbols, files, and repair commit are from
> the prior implementation and are recorded in this project's internal port
> record. None of them exists here. What ports is the failure mode, not the
> names — the shape of the mistake is what recurs.

## The rule reaches prose, not just identifiers

A shared abstraction's scaladoc and inline comments leak Tier-2 vocabulary as
readily as its identifier does. A shared base described in prose as "the
`<fork name>` opcode list" carries the identical conflation risk as naming it
that way, even where the identifier itself is correctly Tier-1.

Write a shared base's documentation in Tier-1 vocabulary. Reserve Tier-2 fork
names for the family-local leaf's own documentation, where they correctly
describe that family's release.

The prior implementation's worked shape: a chain of shared bases each named and
documented by proposal number, with fork-named family-local leaves at the bottom
extending them. The shared chain's documentation named no fork; only the leaves
did.

## Substitutions to reach for

| Where you were about to write | At the shared level, use |
|---|---|
| An event name for a consensus transition | The consensus mechanism, or "consensus transition" |
| A fork name for a fee-mechanism era | The proposal number that defines it |
| A fork name for a *shared* abstraction | The proposal number. Keep fork names, but only as family-local labels |
| A network-specific term for a general layer | The neutral layer name |

## There is no grep for this, and the reason matters

Unlike a language-idiom rule, this one has **no mechanical check**, and one is
not straightforward to build:

- Tier-2 fork names are **correct and expected** in family-local symbols. A
  textual search for them cannot distinguish a legitimate family-local label
  from a leaked shared abstraction. **The violation is structural — does this
  symbol's definition live in a shared path and get read by both families? — not
  lexical.**
- Consensus-transition event names are closer to searchable, since there is no
  legitimate reason for one to name a framework-level abstraction. But each hit
  still needs reading, to confirm it is not correctly describing one network's
  actual history in that network's own scoped documentation.

This is `.claude/rules/evidence-and-citation.md` §3 arrived at independently: a
search returns candidates, and a finding requires opening the file. **Do not
write a check here that reports clean because it matched nothing** — a search
whose pass state is unreachable, or whose hits are all legitimate, trains
everyone to ignore it.

If a genuinely lexical pattern is ever identified — a specific identifier that
is always wrong — record it here as a check. Until then this rule is enforced by
review, which is what review is for.

## Two things deliberately not carried forward

**A carve-out for one config field** that bears a Tier-2 event name inside a
shared configuration container, justified by three conditions about that
container's structure and defaults. Its conditions reference a configuration
type that does not exist here. Re-derive it if and when the same situation
arises; do not restore the conclusion without its conditions.

**A note exempting one agent's name from this rule.** The roster it referred to
is not this project's roster.
