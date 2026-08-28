# Evidence and citation discipline

**No `paths:` frontmatter, deliberately.** A `paths:` list would scope this to
sessions that happen to read a matching file, and the failures below are not
file-shaped: they are claim-shaped. An absence claim gets made while reading
nothing. So this loads at launch with the same priority as the root `CLAUDE.md`,
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
`ethereumclassic/ECIPs`, `ethereum/EIPs`, `ethereum/hive` and
`scala/docs.scala-lang` are **full clones that simply never tag**. Their SHAs are fully trustworthy and they need no
fixing. A shallow clone is different: the SHA is real but the history behind it
is missing, so it cannot answer "what did this look like at version X."

**The specs are the untagged case, and they are the most-cited corpus.** ECIPs
and EIPs carry zero tags. Cite the document number and the commit SHA
(`ECIP-1100 @ <sha>`), never the branch.

**For the Olympia ECIPs, citing is the whole of what is permitted: no document in
this repository restates their content.** That suite is under active rewrite, so a
restated value, membership list or design summary does not merely age — it goes on
describing a mechanism the specification no longer contains, while still reading as
sourced. Name the ECIP and the concern; read the content from the specification at
the moment you need it. `.claude/reference-corpus.md` § "Cite an Olympia ECIP;
never restate what it contains" is the authority, including why no roster of the
suite is recorded anywhere. **This is stated here because that file does not
auto-load and this one does.**

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

**And that zero is a LINE-based zero, which wrapped prose defeats.** `git grep`
matches within one line, and a corrected phrase in a paragraph that wraps
routinely spans a newline — so the sweep returns the zero you were hoping for
while the old wording sits whole in the file you just edited. Measured here with
a control: a phrase present in a tracked file returned zero from `git grep` and a
hit from a newline-aware match of the same words, while a phrase sitting on one
line returned a hit both ways. **It has recurred four times, across four
actors**, which makes it the most durable instance of the class section 3 names.

**Two things that recipe has to get right, both measured.** The break can fall
at *any* inter-word position, so `\s+` goes at every one of them — a pattern
carrying it at a single guessed boundary returned zero against the real wrapped
phrase while still matching its own unwrapped control, which is a pattern that
works and is aimed one word wide. And section 2 requires a sweep of the tree,
not of a file, so the newline-aware form needs the file list fed to it:

```bash
git grep -n 'the exact old phrase'                     # line-based: necessary, not sufficient
git ls-files -z | xargs -0 /bin/grep -Pzol \
  'the\s+exact\s+old\s+phrase'                        # newline-aware, whole tree
```

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

### The control must be a member of the class you are counting

**A calibration proves the instrument ran. It proves the PATTERN discriminates only if the control is
something the pattern itself must match.** Those are different claims, and the second is the one a
zero depends on.

The failing shape, which looks like diligence:

```
grep -c 'the thing I am looking for' file    # -> 0
grep -c 'some token I know is present' file  # -> 94   "instrument fires"
```

The second line establishes that `grep` works and the file is readable. **It establishes nothing about
whether the first pattern would have matched had the thing been there** — a mistyped pattern, a wrong
word boundary, or a phrase that lives somewhere else entirely all still report zero.

**Calibrate with a KNOWN POSITIVE FOR THE PATTERN, and a known negative for the same pattern.** Run
the pattern against a file, a fixture, or a synthesized line that must match; confirm it does. Then
run it where it must not; confirm it does not. Only then is a zero elsewhere a finding.

**Measured, repeatedly, and by more than one kind of actor here:**

- An earlier review record's F9 calibrated a zero with *"94 hits for `review`"* — a present token
  unrelated to the pattern under test. The pattern was wrong and the zero was wrong.
- A charter audit reported a discipline present in a charter. The pattern was
  `invariant, not a name\|not the name\|...`, and what matched was **`not the names above`** in an
  unrelated sentence about directory listings. The true count was zero; the discipline lives in a
  rule, not a charter, and the wrong reading nearly produced a restructure of seven files.
- The same audit's own sweep reported seven hits for `\bspec` — four `specific`, two `specialist`, one
  `specialty`, and **none of them `specification`**, which was the word under test.

**The tell: your control and your pattern have nothing in common.** If the token you calibrated with
could not have been matched by the pattern you are trusting, you calibrated the tool and not the
question.

**This applies to any instrument with a pass state, not only to `grep`** — a sweep, a gate, a proof
arm, a test. `scripts/*-proof.sh` is this discipline made mechanical, and **it takes both halves**:
its positive arms seed the very defect the check exists to catch and require the check to fail **with
the exact exit code that defect must produce**; its negative arms seed a known-good input and require
it to pass.

**Both halves, because the first half alone reads as the whole rule** — and stated alone it licenses
deleting the very negative controls the paragraph above mandates. "Requires the check to fail" is
also weaker than what those scripts actually assert: arms distinguish exit 1 from 2 from 3 from 97, and an arm that accepts
any non-zero exit cannot tell the seeded defect from an unrelated breakage. That looseness has already
shipped here as a placebo arm that passed on a path-resolution error while printing a conclusion about
a mutation it never reached.

### An instrument that only answers is not always a search

**Failure it prevents:** five instruments in one section, not one of them a
check, each returning a measurement that was plausible, confident and wrong.
Four different actors produced them.

**What the paragraphs above already cover, said plainly, because the boundary
is not where it first looks.** They state the recipe this subsection needs — a
known positive and a known negative for the same pattern — and they state it
about a **search pattern**. Title, failing shape, all three measured instances
and the tell are search, and `grep -c` is itself an instrument that only answers:
it returns a count for every input it will ever be handed. **The recipe
generalizes; the recognition does not.** A reader holding a transcribed formula,
a dictionary accessor or an in-place substitution does not see a pattern, so
nothing above tells them the recipe is theirs. **This subsection adds an artifact
class, not a technique.**

**The recognition property.** An instrument that only answers takes an input and
returns a number or a label, and returns one for every input it will ever be
handed. There is no state in which it declines, and a wrong answer is the same
shape as a right one. A decoder, a classifier, an extractor, a formula, an
in-place edit, a name-collecting sweep — and `grep -c`, which is why the
subsection above is a special case of this one rather than a different subject.
Call what any of them produces **an uncalibrated answer** until it has been
calibrated, so the defect can be named in a review comment.

**These fail toward a clean result, which is what they share with everything
else in this file.** Five, measured here in one section:

- **An in-place edit that matched no line.** A mutation battery driven by a text
  substitution whose anchor had moved: the substitution applied to nothing, the
  battery ran against an unmutated copy, and it reported that nothing escaped.
  **Its answer was "the defect you are hunting is caught by nothing" — the one
  answer that ends an investigation rather than prompting the next step.** That
  direction is why this case carries the rule.
- **A character class that could not match what it was collecting.** A sweep
  gathering type names was written `[A-Za-z]*` where some of those names carry
  digits, so it returned a smaller set of coverage rows with nothing missing on
  the face of them.
- **A default that hid an absence.** A field read through a `get(name, default)`
  accessor cannot tell a field stated as the default from a field that is not
  there. The field was absent from every case in the corpus, and every case was
  classified as stating it.
- **A formula transcribed one exponent short.** A curve-point classifier
  computed a doubling slope as `3x/2y` where the curve gives `3x²/2y`, so it
  reported almost every point as outside the subgroup — including the subgroup's
  own generator.
- **A line-based sweep over prose that wraps.** Section 2's zero-hit check is
  exactly this shape, and states the remedy there.

**And an answer can be arity-clean and still be wrongly keyed.** `grep -h` over
several files discards the filename rather than the order — GNU grep emits
argument order, measured — so what is lost is which file each line came from. A
mapping built by zipping the file list against that output is correct only while
every file contributes exactly one line. Measured here: four files, four matching
lines, the two counts agreeing exactly, and the mapping wrong, because one file
matched nothing and another matched twice. `-H` keeps the key and costs nothing.
**A count that reconciles is not evidence the rows are paired correctly**, and
that is the one property a check on the total cannot reach.

**The remedy is the control subsection's, unchanged, and is deliberately not
restated here: a known positive and a known negative, run before the instrument
is trusted on an answer nobody knows.** What this subsection adds is knowing to
reach for it when what you are holding is a formula rather than a pattern.

**Both halves of this have established names, and reaching for them buys the
literature.** Running a fixed input whose correct output is published, before the
thing that computes it is used, is a **known-answer test** — the form
cryptographic-module validation requires at initialization. The general
difficulty, that deciding whether an output is correct is a separate problem from
producing it, is the **test oracle problem** (Barr, Harman, McMinn, Shahbaz and
Yoo, *The Oracle Problem in Software Testing: A Survey*, IEEE Transactions on
Software Engineering 41(5), 2015). "An uncalibrated answer" stays as the local
name for the defect, because it names what is wrong rather than what to do about
it.

**Where no known answer is available, state what the instrument cannot see.**
That is the absence rule above arriving at a measurement rather than at a search.
*"This collects the names matching `[A-Za-z]*Corpus`"* is a true and smaller
claim than *"these are the coverage rows."*

**Mechanized here in two places, and nowhere beyond them.**
`scripts/gen-altbn128-vectors.py` is the worked case: a `calibrate()` that runs
before the generator does any work, requires the classifier to admit the group's
own generator, requires it to reject a point moved one step off the curve, and
exits on either. Both directions, before the first real answer is produced. The
tracked proof scripts and the hook mutation batteries do the same for the first
case above — each refuses to score a mutation whose anchor moved rather than
counting it as survived.

**Nothing mechanizes this for an instrument written for one question, and
nothing here proposes to.** A number from a calibrated instrument and a number
from a broken one are the same number, and that indistinguishability is the
definition of the class rather than a gap a hook could close. What is available
instead is placing the calibration in the script beside the answer, where a
reader can see that it ran — and, where the instrument was a one-off, reporting
what it was calibrated against alongside what it found.

**Rule 5 is the sibling and not the same rule.** It governs a claim stated one
step past a measurement. This governs the instrument that produced the
measurement. Both run toward confidence, and neither is caught by re-reading
what you wrote.

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

### Reachable is necessary, and it is not sufficient

**A second axis crosses the first, and nothing above implies it.** Everything so
far asks whether a reader who clones can *follow* a citation. A citation can pass
that test completely — a public repository, a public organization, an immutable
ref anyone can fetch — and still be one this project does not put in tracked
text. **Reachability licenses a name; it never obliges one.**

**Tracked text names artifacts, never actors.** Naming a repository, an
organization or a ref as the *location of evidence* is what the rule above is
for. Characterizing an organization's conduct, naming an individual or
attributing a motive is not evidence about code, is durable from the moment it is
pushed, and asks a reader to accept a claim about people that they have no
instrument to check.

**And a small number of publicly-reachable subjects are deliberately not named
at all.** Which ones is recorded machine-locally in `CLAUDE.local.md`, and is
deliberately not enumerated here: **a tracked list of what must not be said in
tracked text publishes precisely what it was written to withhold.** So these
rules can state that the category exists and cannot state its membership — that
asymmetry is the design, not an omission for a later pass to tidy up.

**The trigger is mechanical, which is the only thing that makes this
followable.** Before naming an organization, a repository, a project or a person
in tracked text — a commit message included — check that authority. It is one
file and the check costs seconds. Where it is silent the reachability test above
is the whole of the rule, and nothing here narrows it.

**The error is the reviewer's more often than the author's, and it runs toward
disclosure.** Text that names less than it could reads as over-corrected, and the
instinct is to restore the name — an instinct that feels principled, because the
warning against over-correction is itself a real rule this repository states in
more than one place. Where a subject is deliberately unnamed the sparse text is
correct, the recommendation to name it is the defect, and **the act of
correcting it is what publishes it.** The asymmetry is not close: a name withheld
that could have been given costs a reader one lookup, and a name given that
should have been withheld cannot be recalled.

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

## 5. An inference from a verified fact is not verified

**Failure it prevents:** four claims in one section, each built on something
genuinely measured, each wrong in the step past the measurement — and each
stated with the confidence the measurement earned.

**The mechanism, because it is not carelessness and does not feel like
guessing.** You check something. You then state a second thing that stands next
to it: what the checked thing is *for*, what it is a *part* of, whether it is
*still* true, what it *generalizes* to. **The second thing inherits the felt
certainty of the first without inheriting its verification**, and the adjacency
is the disguise. Nothing about it reads as a guess, because you were holding
real evidence a moment earlier.

The four shapes, which recur:

| Checked | Stated, unchecked |
|---|---|
| Several counts inside a file | the **denominator** they are counts *of* |
| A record says a defect exists | that the record is **still current** |
| One implementation does X | that **every** implementation does |
| A field exists and is named N | what that field is **for** |

**The last one is the sharpest, because a name is the most persuasive
unverified thing in a codebase.** A field named for a concept is not evidence
that it implements that concept, and in one measured instance a field whose
name matched a consensus rule exactly had a single reader, in a component that
decides no consensus question at all. `reference-first.md` states the same
hazard for a name adopted along with a shape; this is that hazard arriving
through summary rather than through survey.

### The remedy: mark what you did not check, especially in a brief

**A claim handed to another agent is acted on, not evaluated.** Prose in a brief
carries no gradation — a measurement and an assumption sit in the same sentence
shape, and the agent has no way to tell them apart. So the brief must:

- **Say which claims may be relied on**, in the form *verified here, do not
  re-derive* — and mean it, having actually run the check.
- **Say which are open**, in the form *not verified, treat as a hypothesis* —
  naming the instrument the agent should use if it matters.

Two lines, not per-claim tagging. **Where this split has been written, agents
have re-derived the open half and found the error. Where it was dropped, the
inference reached the work as an instruction.** That difference has been
observed in both directions in a single section.

**The same marking belongs on any durable record another reader will act on.**
A survey record that tags each finding as verified, attributed, or bounded
is the same discipline at document scale, and it is the reason a reader can
trust the strong claims in one — the weak ones are visible as weak.

### Do not resolve to be more careful

Care is what produced these: every one came from a session actively checking
things. **The failure is structural — verification does not transfer across an
inference — so the remedy has to be structural too.** Marking is cheap and
survives fatigue; resolve is neither.
