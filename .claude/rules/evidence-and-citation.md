# Evidence and citation discipline

**No `paths:` frontmatter, deliberately.** A `paths:` list would scope this to
sessions that happen to read a matching file, and the failures below are not
file-shaped: they are claim-shaped. An absence claim gets made while reading
nothing. So this loads at launch with the same priority as `.claude/CLAUDE.md`,
and it reaches dispatched subagents — the only delivery mechanism that works for
a discipline every agent must apply.

**With one documented exception, and it is the worst possible one for this file.**
The built-in `Explore` and `Plan` agents skip the rules hierarchy entirely, and
there is no frontmatter field or per-agent setting that changes it. **`Explore`
is the search specialist** — precisely the agent that most needs §3's "a grep is
a search, not a finding" and "an absence claim needs a corpus," and precisely the
one that will never load it. The vendor's own remedy is the only one available:
**restate the rule in the delegation prompt** when dispatching either. Stated
here rather than quietly omitted, because a rule that overstates its own reach is
the same defect class as the ones below it.

Each rule here is anchored to a failure that actually happened in this
repository's prior attempt, not to a general principle. The counts are from that
attempt's independent audit.

---

## 1. Cite the ref, never a moving pointer

**Failure it prevents:** a clone's `main` was cited for a version the build
pinned. `main` moved; the citation silently began describing something else.

**A citation names a ref that cannot move.** A repository name is not a
citation. `main`, `master`, `HEAD`, and "the clone" are not citations, because
every one of them describes a different tree next week.

The corpus cannot always satisfy the strongest form, so this is a decision
procedure rather than a single rule. **A meaningful minority of the reference
clones are shallow, untagged, or both**, and which ones changes as the corpus
grows — so establish it per clone at the moment you cite, rather than trusting a
number written here.

```bash
git -C <clone> rev-parse --is-shallow-repository   # true => history truncated
git -C <clone> tag | head -1                       # empty => untagged
```

**Enumerate the corpus with an instrument that reaches all of it.** A depth-bounded
`find` silently misses clones nested deeper than the bound, and returns a smaller
number that looks like an answer. **The instrument's reach produced the number,
not the tree** — which is this rule's own subject matter, and the reason no count
is recorded here to be maintained.

| The clone has | Cite | Example |
|---|---|---|
| Tags, and one covers your claim | **the tag** | `sbt/sbt @ v2.0.4` |
| No tags, full history | **the commit SHA + date** | `ethereum/EIPs @ a1b2c3d (2026-07-30)` |
| Shallow history | **the SHA, plus "HEAD-only"** | `ethereum/tests @ 9f8e7d6, HEAD-only (shallow clone; the ref is real, the history behind it is truncated, so this cannot show the state at any earlier version)` |

**Untagged is not the same defect as shallow, and the remedy differs.**
`IPs/ECIPs`, `IPs/EIPs`, `ethereum/hive` and `docs.scala-lang` are **full clones
that simply never tag**. Their SHAs are fully trustworthy and they need no
fixing. A shallow clone is different: the SHA is real but the history behind it
is missing, so it cannot answer "what did this look like at version X."

**The specs are the untagged case, and they are the most-cited corpus.** ECIPs
and EIPs carry zero tags. Cite the document number and the commit SHA
(`ECIP-1100 @ <sha>`), never the branch.

**A version claim is answered by the registry, never by a clone.** A clone shows
what someone fetched once. Maven Central and Scaladex show what is published.
Corpora are cloned; registries and indexes are queried live. A clone of an
index's source gives you its code, not its data.

## 2. Correct in one place, then prove the old wording is gone

**Failure it prevents:** a correction was added while the original survived
elsewhere, so the document then asserted both. That is worse than never
correcting it, because the reader now has two sources and no way to tell which
one is current.

**After correcting any document, grep the old wording across the tree. It must
return zero.** Not "mostly gone." Zero, or an explicit note saying where a
surviving instance is deliberate and why.

```bash
git grep -n 'the exact old phrase'      # must return nothing
```

This is a search, so section 3 applies to it: search for a phrase distinctive
enough that a hit means what you think it means.

**Correcting in place beats appending a correction.** A note saying "this was
wrong, see below" leaves the wrong statement readable. Replace the claim, and
where the error itself is instructive, state the correction as the new text
rather than as an annotation on old text.

## 3. Instrument discipline

**Failures it prevents:** substring matches reported as findings (3+ times), and
absence claims drawn from a single instrument (4+ times).

### A grep is a search, not a finding

`grep -i mess` matches `message`, `messenger`, and `assessment`. A search
returns candidates. **A finding requires opening the file and reading the
match in context.** Reporting a match count as a result is reporting the
instrument's behavior, not the codebase's.

Before reporting any grep as evidence, state what it would have matched that you
did not want, and confirm you excluded it.

### An absence claim needs a corpus, not one instrument

"Nothing implements X" and "no page says Y" are claims about **everything**. One
instrument sees one member — a filesystem search cannot see a hosted service, one
doc page cannot see the page it links to, one summarized fetch cannot see what it
omitted.

Either enumerate the corpus, or **write the smaller true claim that names what
you checked**:

- Not: "ETC does not implement MESS."
- But: "`besu-etc` at `<sha>` contains no MESS implementation. besu-etc was
  never a mainstream client and is known-incomplete, so this is not evidence
  about ETC."

That second form is the worked example this repository already owns, and it is
the shape to copy: **an absence in one client is not an absence in the network.**

### Search for an invariant, not a name

A name is the thing most likely to have changed, been aliased, or been spelled
differently. Search for the thing that cannot change: a magic constant, a
selector, an opcode value, a test vector, an error code.

Looking for a gas-cost rule, search the number. Looking for a fork activation,
search the block height. A search for the fork's *name* finds the comments and
misses the implementation.

### A second pass, differently patterned, before "clean"

One pass is not evidence of completeness. Before declaring a pattern family
clean, run the sweep again with a **differently worded** pattern set — synonyms,
partial matches, adjacent field and method names. If the two passes disagree,
the broader count wins, and **the disagreement is itself the finding**: it means
the family is more slippery than one pattern can capture.

### The Scala blind spot: a symbol consumed through `using`

The absence rule above has a specific, structural instance in this codebase, and
it is invisible rather than merely missed.

**A `given` instance summoned by the compiler's implicit search is named
nowhere.** It has zero textual references and is not dead. No grep, however
broadly patterned, can see the consumer — this is not a weak search, it is a
search looking for something that was never written down.

So a "no callers" claim about anything reachable by implicit resolution is
closed **by removal and compile**, never by searching. Until that has been done,
the honest phrasing is *"a search shows no references — confirm by removal and
compile"*, never *"confirmed dead."*

`.claude/protocols/dead-code-review.md` owns what to do with such a candidate;
this section owns why a search cannot close the question.

### A fix applied to the known sites is a partial rollout

When you fix a known-bad pattern across the files already flagged, **sweep for
the same pattern shape across every structurally similar file** — not just the
list you were handed. A partial rollout is a live defect waiting for an unlucky
trigger, and it looks exactly like a completed one.

**The same applies to a wrong claim in prose, and it is the more common case
here.** When a statement turns out to be wrong in one document, grep the *exact
phrase* across the whole tree before treating the fix as scoped to that file.
Copy-paste drift is the documentation form of a partial rollout: a claim
repeated in eleven files, corrected in the two that a reviewer happened to cite,
reads as fixed.

## 4. Tracked text cites nothing a reader cannot reach

**This repository is public. A tracked file never cites a private path.** Not as
a dependency, and not as an elaboration behind a tag — a path under a gitignored
tree resolves for nobody who clones this repo, so in tracked text it is cost
without benefit, and it publishes the name and shape of material that was kept
back on purpose.

**So every rule here states its substance inline and stands on its own.** That
is not a mitigation for the missing path; it is the reason no path is needed.

**The failure this prevents is not a broken link — it is a public document
coupled to a private layout.** Rename a directory that no clone has, and a
tracked rule needs editing. That has happened here, repeatedly, to a tree that
changed names more than once; each time, public text moved because private
storage did. A rule that cites nothing private cannot be dragged that way.

**Where fuller reasoning genuinely exists and cannot be inlined, name it by
what it is rather than by where it lives** — "the durable authority model", "the
record of why each version is what it is". A reader with access will find it; a
reader without one loses nothing they could have used. Machine-local pointers
belong in `CLAUDE.local.md`, which `.gitignore` covers.

**One rule from that authority model is short enough to inline, and load-bearing
enough that it must not depend on anything external:** fukuii's own prior
implementation code — every earlier attempt, whatever it is called and wherever
it is kept — is **never a correctness oracle**. It is a structural guide,
telling you where something lived and what shape it was, never whether a value
is right. Fukuii-authored *test vectors* are a different artifact and are
exempt: where fukuii leads, a reviewed vector is a deliverable that becomes
authority.

**The same non-authority applies to NAMES, and this has to be said rather than
inferred** — because the paragraph above blesses a prior implementation as *"a
structural guide, telling you where something lived and what shape it was"*, and
a name reads far more like *shape* than like a value. A reader reasoning from
that sentence alone could conclude that inheriting a name is exactly what it
licenses. It is not. **Do not carry a prior type or symbol name forward merely
because the shape matches** — that imports an un-reviewed naming choice on the
"it is already there" basis this rule forbids for values, and it is harder to
catch, because a name that fits reads as a name that was chosen.

`.claude/rules/nomenclature.md` states which vocabulary a name may be drawn from
instead. This rule is what stops the prior tree being used as a shortcut past it.

**And name the source, never a label that hides it.** A shorthand like "AS-IS"
for a validation source reads as a neutral methodology step when what it
actually means is "this project's own prior implementation" — which is the
circularity this section exists to prevent, made invisible by the abbreviation.
Name the thing being cited, so the circularity is visible on the surface of the
citation rather than recoverable only by someone who already knows what the
shorthand stands for.
