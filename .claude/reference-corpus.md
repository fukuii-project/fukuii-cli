# Reference corpus — the repositories fukuii cites, and how to rebuild it

**This file is a manifest of public repositories and refs.** Its purpose is that
someone holding a clone of fukuii can assemble the same reference corpus and
check a protocol claim against the same sources, rather than taking this
project's word for it. Every row names a repository anyone can clone and a ref
anyone can check out.

**It is not a dependency list, and a row here is not an adoption.** A clone is
evidence about how something behaves; a dependency is a commitment this project
makes. `AGENTS.md` § Stack states the rule that governs the second — *a
dependency with no present need is not an entry* — and
`.claude/rules/scala-dependency-admissibility.md` is the gate a candidate clears
before it can become one. Nothing may be added to `build.sbt` because it appears
below.

**The corpus itself is machine-local and a clone does not receive it.**
`.gitignore` excludes the directory it lives in, deliberately: these are other
people's repositories, several of them very large, and vendoring them into this
one would be both wrong and impractical. That absence is exactly why this file
exists.

---

## Rebuilding it

Choose a corpus root outside this repository's tracked tree. This project keeps
it at `.local/reference-material/`, which `.gitignore` covers; any location
works, and nothing in the build reads it. Every path in the tables below is
relative to that root, so the layout is reproduced by cloning each row into its
stated path:

```
<corpus-root>/
  clients/etc/…            reference clients for Ethereum Classic
  clients/eth/el/…         Ethereum execution-layer clients
  clients/eth/cl/…         Ethereum consensus-layer clients
  IPs/…                    the specifications: ECIPs and EIPs
  test-suites/…            conformance corpora and wire, API and execution specs
  stack/…                  the toolchain components this repository declares
```

Clone with full history. A truncated clone cannot answer the question a
reference corpus exists to answer — see **Depth** below, which is a rule about
the ref you cite rather than about the repository.

---

## How to cite what is in here

`.claude/rules/evidence-and-citation.md` § 1 is the authority for citation form
and is not restated here. Two consequences bind every row below:

- **Name a ref that cannot move.** A repository name is not a citation. Where a
  row's ref is a tracking branch, cite the branch **and** the commit; where a
  repository carries no tags, a tag citation is not available and inventing one
  is worse than the honest form.
- **A version is answered by a registry, never by a clone.** A clone sits at
  whatever ref someone last fetched. Maven Central and Scaladex answer what is
  published; a `git log` in a clone answers neither.

**Presence in this corpus is not authority.** Authority is granted per question,
by concern and by era, and the model that grants it is this project's durable
authority model — an internal document that does not travel with a clone, so it
is named here by what it is rather than by a path a reader cannot open
(`.claude/rules/evidence-and-citation.md` § 4). The **Authoritative for** column
below carries what a reader outside this project needs in order to use a row
correctly; it is a summary of that model, not a second copy of it, and where the
two disagree the model wins.

Three rules the column depends on, stated once so each row can stay short:

1. **Shared EVM, RLP and cryptography: go-ethereum and besu must agree.** A
   disagreement between them is escalated, never resolved by picking a side.
2. **The specification outranks every implementation.** The ECIP or EIP is the
   spec; a client is the reference implementation of it, and is cited to check a
   value, never to establish one.
3. **An absence in one client is evidence about that client.** It is never
   evidence about the network.

---

## Ethereum Classic clients — `clients/etc/`

> **Three of these six entries are fukuii's own work and are NEVER an
> authority.** They sit under `olympia-wip/`, beside the genuine references and
> under directory names that carry no warning. Checking fukuii against them is
> checking fukuii against fukuii. The trap is live rather than theoretical: a
> reader who learns that besu-etc carries no MESS implementation and then wants a
> JVM reference for it will find exactly one on this layout, and it is ours.
> Worse, `olympia-wip/core-geth` carries **more** `ecbp1100` hits than the
> authority it overlays, so a naive "which tree knows most about this" heuristic
> ranks our draft above the source of truth.

| Path under the corpus root | Upstream | Ref | Authoritative for |
|---|---|---|---|
| `clients/etc/core-geth` | [ethereumclassic/core-geth](https://github.com/ethereumclassic/core-geth) | `master` | **ETC consensus, Frontier through Spiral** — the reference implementation, and what mainnet runs. **The sole external authority for MESS**, which client code spells `ecbp1100` (the registry spells it ECIP-1100; searching the registry's spelling returns zero here). Silent on post-Spiral and Olympia work, which is not disagreement |
| `clients/etc/besu-etc` | [besu-eth/besu](https://github.com/besu-eth/besu) | `etc-frozen` — **pinned, see below** | **JVM implementation shape on the historical ETC era, and nothing else.** It carries ETC's fork schedule and it carries no MESS. An absence here is evidence about besu-etc: it was a reference client during ETC development, never a mainstream deployment, and was never asked to be complete |
| `clients/etc/olympia-wip/besu` | [white-b0x/besu](https://github.com/white-b0x/besu) | `main` | **NOT AN AUTHORITY.** Fukuii's own Olympia overlay. Input, never oracle |
| `clients/etc/olympia-wip/core-geth` | [white-b0x/core-geth](https://github.com/white-b0x/core-geth) | `main` | **NOT AN AUTHORITY.** Fukuii's own Olympia overlay, on top of the authority it shadows. Input, never oracle |
| `clients/etc/olympia-wip/nethermind` | [white-b0x/nethermind](https://github.com/white-b0x/nethermind) | `main` | **NOT AN AUTHORITY.** Fukuii's own Olympia overlay. Input, never oracle |

**`besu-eth/besu` appears twice in this file at two different refs, and the two
trees disagree about whether ETC exists.** The row above is the ETC-bearing one;
the row under **Ethereum execution-layer clients** is besu as a native Ethereum
client. A citation naming only the repository is unusable. The former
`hyperledger/besu` address redirects to `besu-eth/besu`; use the current name.

---

## Ethereum execution-layer clients — `clients/eth/el/`

| Path under the corpus root | Upstream | Ref | Authoritative for |
|---|---|---|---|
| `clients/eth/el/go-ethereum` | [ethereum/go-ethereum](https://github.com/ethereum/go-ethereum) | `master` | **The reference implementation of ETH-family consensus.** Half of the shared-EVM agreement pair (rule 1 above) |
| `clients/eth/el/besu` | [besu-eth/besu](https://github.com/besu-eth/besu) | `main` | The other half of the shared-EVM agreement pair, and **JVM implementation shape** on the Ethereum side. This ref has no ETC support |
| `clients/eth/el/erigon` | [erigontech/erigon](https://github.com/erigontech/erigon) | `main` | **Framework structure** — per-family module layout |
| `clients/eth/el/nethermind` | [NethermindEth/nethermind](https://github.com/NethermindEth/nethermind) | `master` | **Framework structure** — the consensus-plugin and chain-spec-parameter seams |
| `clients/eth/el/reth` | [paradigmxyz/reth](https://github.com/paradigmxyz/reth) | `main` | **Framework structure** — node-types and fork-condition types |
| `clients/eth/el/ethrex` | [lambdaclass/ethrex](https://github.com/lambdaclass/ethrex) | `main` | **Framework structure** — a recent ground-up client, useful as a second reading of the same seams |

**The framework-structure rows are a deliberate carve-out and are routinely
over-read in both directions.** A modern client is **not** an authority for an
ETC byte value — a gas figure, a constant, an activation block. It **is** an
authority for how a multi-family client is structured, which is a different
question and the one fukuii's architecture actually poses. Read across at least
two families and two languages; a single implementation is the seam plus that
codebase's habits.

---

## Ethereum consensus-layer clients — `clients/eth/cl/`

Authoritative for the **consumer side of the Engine API** and for **framework
structure** across implementation languages. None is an authority for Ethereum
Classic, which has no consensus layer.

| Path under the corpus root | Upstream | Ref |
|---|---|---|
| `clients/eth/cl/teku` | [Consensys/teku](https://github.com/Consensys/teku) | `master` |
| `clients/eth/cl/lighthouse` | [sigp/lighthouse](https://github.com/sigp/lighthouse) | `stable` |
| `clients/eth/cl/prysm` | [prysmaticlabs/prysm](https://github.com/prysmaticlabs/prysm) | `develop` |
| `clients/eth/cl/nimbus-eth2` | [status-im/nimbus-eth2](https://github.com/status-im/nimbus-eth2) | `stable` |
| `clients/eth/cl/lodestar` | [ChainSafe/lodestar](https://github.com/ChainSafe/lodestar) | `unstable` |
| `clients/eth/cl/grandine` | [grandinetech/grandine](https://github.com/grandinetech/grandine) | `develop` |

**Each upstream's own default branch differs, and the ref column reflects what
that project publishes as its development or release line.** Do not normalize
them to `main`.

---

## The specifications — `IPs/`

| Path under the corpus root | Upstream | Ref | Authoritative for |
|---|---|---|---|
| `IPs/ECIPs` | [ethereumclassic/ECIPs](https://github.com/ethereumclassic/ECIPs) | `master` | **The specification for ETC consensus.** Authoritative and complete for every ECIP **except the Olympia set**, which is still being drafted by this project's maintainer and reaches the published repository after implementation and testing — **see below before citing anything Olympia** |
| `IPs/EIPs` | [ethereum/EIPs](https://github.com/ethereum/EIPs) | `master` | **The specification for ETH-family consensus.** Many EIPs apply to ETC identically and ETH leads on those; where ETC carries a different parameter for an otherwise-identical EIP, the ECIP governs the parameter |
| `IPs/ERCs` | [ethereum/ERCs](https://github.com/ethereum/ERCs) | `master` | **The application-layer standards series, and not optional for a client.** Documents that began in the EIPs repository were moved here and the EIPs clone retains only a stub naming the new location — **so a document can be "in EIPs" and unreadable there.** ERC-55, the mixed-case address checksum, is the instance L0 meets first |

**None of these three repositories carries a single tag, so a tag citation is
structurally unavailable for all of them.** Cite by **document number and commit
SHA** — never by branch, and never by repository alone.

**Check both series before concluding a document is missing.** A proposal's
number does not say which repository holds its text, and the stub left behind
reads like a dead end rather than a redirect. This was found the ordinary way:
by opening `EIPS/eip-55.md` for its test vectors and getting a one-line pointer.

---

## Conformance corpora and protocol specifications — `test-suites/`

| Path under the corpus root | Upstream | Ref | Authoritative for |
|---|---|---|---|
| `test-suites/ethereum/tests` | [ethereum/tests](https://github.com/ethereum/tests) | `develop` | The primary Ethereum conformance corpus |
| `test-suites/core-geth-tests` | [etclabscore/tests](https://github.com/etclabscore/tests) | `develop` | The Ethereum corpus as core-geth consumes it |
| `test-suites/fukuii-etc-tests` | [etclabscore/tests](https://github.com/etclabscore/tests) | `main` | **The corpus fukuii hosts** — every known ETC test in one place, and the destination for the vectors fukuii authors. Its authority is **prospective**: its current content is upstream ETC-translation work with no fukuii-authored commits, so citing it as fukuii's answer to a historical question is circular. A fukuii vector becomes authority when it is derived from the spec and cleared by a reviewer who did not author it |
| `test-suites/ethereum/consensus-specs` | [ethereum/consensus-specs](https://github.com/ethereum/consensus-specs) | `master` | The consensus-layer specification and its own test vectors |
| `test-suites/ethereum/execution-apis` | [ethereum/execution-apis](https://github.com/ethereum/execution-apis) | `main` | The JSON-RPC and Engine API specification |
| `test-suites/ethereum/devp2p` | [ethereum/devp2p](https://github.com/ethereum/devp2p) | `master` | The peer-to-peer wire specification: RLPx, discovery, and the ETH protocol versions |
| `test-suites/ethereum/hive` | [ethereum/hive](https://github.com/ethereum/hive) | `master` | The cross-client integration harness — how conformance is exercised, rather than what conformance is |
| `test-suites/ethereum/yellowpaper` | [ethereum/yellowpaper](https://github.com/ethereum/yellowpaper) | `master` | The formal specification of the EVM |

**`etclabscore/tests` appears twice at two refs, and they are not
interchangeable**: `develop` is a strict ancestor of `main`, which adds the ETC
translation. This is the same shape as the two besu rows — one repository, two
deliberately different sources — and it is why a citation must name the ref.

`test-suites/fukuii-etc-tests`'s `main` is also published at
[white-b0x/fukuii-etc-tests](https://github.com/white-b0x/fukuii-etc-tests) at
the same commit; either remote reconstructs the same tree.

---

## Toolchain — `stack/`

**Only components this repository already declares appear here.** The versions
are in `build.sbt`, `project/build.properties` and `.sdkmanrc`, so listing the
sources discloses nothing that the build does not already publish. **That is the
admission test, and it is a test rather than a list**: a library this project has
not declared does not get a row, because a public entry naming it would record a
decision that `sentinel` and
`.claude/rules/scala-dependency-admissibility.md` have not made. When such a
library is declared, its row becomes admissible then.

| Path under the corpus root | Upstream | Ref | Authoritative for |
|---|---|---|---|
| `stack/scala/scala3` | [scala/scala3](https://github.com/scala/scala3) | a release tag | Compiler behavior at a named release — diagnostics, flag names, TASTy versioning |
| `stack/sbt/sbt` | [sbt/sbt](https://github.com/sbt/sbt) | a release tag | Build-tool behavior at a named release, including task semantics and the caching behavior `AGENTS.md` § Commands documents |
| `stack/sbt/zinc` | [sbt/zinc](https://github.com/sbt/zinc) | a release tag | Incremental-compilation behavior underneath sbt |
| `stack/scalatest/scalatest` | [scalatest/scalatest](https://github.com/scalatest/scalatest) | a release tag | Test-framework behavior at a named release: style artifacts, deprecations, what each artifact pulls transitively |

**Cite these by tag.** Every row in this table is a tagged repository, so the
weaker branch-plus-commit form is not needed and should not be used — and a row
whose upstream carried no tags would not belong here, because a toolchain
component is cited at the release the build declares. A checked-out clone will sit
at whatever ref someone last fetched, which is why the ref column names a *kind*
of ref rather than one value: the ref to cite is the release the build declares,
not the ref the clone happens to be on.

**Documentation sites are deliberately absent.** Scala's and sbt's documentation
is served publicly and needs no clone to reach, so a row for it would add a
maintenance surface and no reach.

---

## Refs that are deliberately pinned

Most rows above name a tracking branch, and no commit is recorded for them on
purpose: a SHA for a moving branch is a maintained value that is stale within
days. Two rows are different, because there the **ref is the artifact**.

### `clients/etc/besu-etc` @ `eb4248c997`, branch `etc-frozen`

**The freeze is deliberate and this ref must not be re-pointed or updated.**
It holds besu's Ethereum Classic and Mordor support, which `main` no longer
carries, so it is the state of that codebase in which ETC exists at all. Being
far behind `main` is the point rather than a defect.

Verifiable from the clone: `etc-frozen` is a strict ancestor of `origin/main` —
every commit on it is also on `main`, and none of `main`'s later commits is on it
— and the ETC-specific paths present at the freeze are absent from `main`.

```
git -C <corpus-root>/clients/etc/besu-etc merge-base --is-ancestor HEAD origin/main
git -C <corpus-root>/clients/etc/besu-etc rev-list --left-right --count origin/main...HEAD
```

The second command's right-hand number is zero, and stays zero: a non-zero value
means someone committed onto the freeze.

**How the freeze is protected, stated because there is no lock and its absence is
deliberate.** `etc-frozen` has **no upstream tracking branch configured**, so a
bare `git pull` on it cannot fast-forward it — verified by effect, not assumed.
That blocks the realistic accidental path.

**`git fetch` here is harmless and must not be prevented.** Fetching moves only
remote-tracking refs; it never moves a branch. What moves a branch is `pull`,
`merge` or `reset`, and only an explicit `git pull <remote> <branch>` can reach
this one — which is a deliberate act, not an accident.

**So no mechanism blocks it, and adding one would be a control with nothing to
control.** What exists instead is a recovery pointer, placed inside the clone
rather than only in this file:

```
git -C <corpus-root>/clients/etc/besu-etc tag --list etc-frozen-baseline
git -C <corpus-root>/clients/etc/besu-etc reset --hard etc-frozen-baseline
```

The annotated tag `etc-frozen-baseline` names the freeze commit and carries the
reason. **A tag blocks nothing, so it cannot be miscalibrated** — its whole job
is that someone who has already moved the branch finds the way back where they
are standing, instead of needing to know this document exists.

### `IPs/ECIPs` — cite the published specification, and know what it does not yet contain

**Clone [ethereumclassic/ECIPs](https://github.com/ethereumclassic/ECIPs) at
`master`.** That is the published Ethereum Classic specification, it is what a
reader of this repository can reach and verify, and it is what every citation in
tracked text names.

**For Olympia specifically, the published specification is not yet complete, and
that is a property of the work rather than a gap in this corpus.** The Olympia
proposals are authored by this project's maintainer, who is a core developer on
Ethereum Classic. They are **active drafting work, and they reach
`ethereumclassic/ECIPs` once implementation is done and testing confirms them
accurate.** Until then the published repository does not carry their current
text.

**Two consequences, and the second is the one to hold on to:**

1. **An Olympia claim cannot yet be checked against a published specification.**
   This is the same fact as core-geth being silent on Olympia — no external
   authority exists for it yet, in either the specification or any client. A
   review that wants an independent Olympia source will not find one, and
   **that absence is the condition, not a search to run harder.**
2. **So do not treat a fukuii implementation, a fukuii overlay, or a fukuii draft
   as the specification it is meant to satisfy.** Validating our Olympia work
   against our own Olympia material is circular whichever artifact is used. Where
   an Olympia detail is load-bearing, it is settled by the maintainer as the
   proposals' author, and recorded as a decision — never inferred from our own
   code.

**For every non-Olympia ECIP, the published repository is authoritative and
complete**, and citations are by document number plus commit SHA, because the
repository carries no tags.

---

## Depth — a property of the ref, not of the repository

**A reference clone exists to answer "what did this look like at version X." A
shallow clone structurally cannot, so shallow is a defect to fix rather than a
property to note.**

The remedy is `git fetch --unshallow`, **never a re-clone**: it preserves the
working tree, any local branches, and any deliberately pinned state. A re-clone
throws all three away, and the pinned refs above are exactly what would be lost.

**Verify an unshallow with two conditions, not one.** The repository flag must
have flipped **and** the commit count behind the ref must have grown. A flag flip
with no new history means the fetch did nothing.

```
git -C <clone> rev-parse --is-shallow-repository     # repository-level flag
git -C <clone> rev-list --count <ref>                # history behind the ref you cite
```

**The flag is repository-level and the question is per-ref.** A repository can
report shallow because of a boundary on a branch you never cite, while the ref
you do cite has complete history. The precise test is whether any shallow
boundary is an ancestor of that ref:

```
git -C <clone> merge-base --is-ancestor <boundary> <ref>   # exit 0 = the ref IS truncated
```

with each `<boundary>` read from the clone's `.git/shallow`. The cheap sanity
check is to read the oldest commit reachable from the ref and see whether it is
the project's genuine first commit:

```
git -C <clone> log --reverse --format='%h %ad %s' --date=short <ref> | head -1
```

### The deliberate exceptions

Everything else in the corpus is a full clone. These are not, each for a stated
reason, so that nobody "fixes" them:

1. **`clients/etc/olympia-wip/core-geth` reports shallow, and its `main` is
   complete to the project's first commit.** The boundary is not an ancestor of
   that ref: it sits on upstream devnet branches fetched from a second remote,
   and `--unshallow` against `origin` cannot clear it, because `origin` does not
   carry those branches. The per-ref test above is what distinguishes this from a
   real truncation, and it is the reason that test is stated as per-ref.
2. **That clone's `tests/testdata` and `tests/testdata-etc` submodules stay
   shallow deliberately.** They are test fixtures rather than cited client code,
   and they are large enough truncated that full history buys nothing this
   project needs.
3. **Staging areas outside the trees listed above are not depth-checked at all**,
   being outside this register entirely — see the next section.

---

## What is deliberately excluded

A reader reconstructing the corpus from this file will not get everything on the
authoring machine, and the gaps are decisions.

- **Candidate libraries under consideration.** The corpus holds a directory of
  clones of libraries that have been *looked at* and adopted by nothing. Naming
  them here would publish "fukuii is considering X" for each one, which is a
  recorded decision that neither `sentinel` nor
  `.claude/rules/scala-dependency-admissibility.md` has made. **A clone is cheap;
  a public entry is a commitment.** They become admissible individually, if and
  when each is declared in the build.
- **Temporary staging.** Clones held so that already-fetched repositories need
  not be fetched again are staging, not a decision, and the area is removed once
  the build no longer needs it. A clone there means nothing.
- **Pekko.** `AGENTS.md` § Stack records its status — decided, not yet declared —
  and is the authority for it. A row here would be a second place stating the
  same thing, and the two would drift.

---

## Checking this file against the corpus

The register carries no counts and no per-entry SHAs for tracking branches, so
there is nothing in it that goes stale on its own. What can drift is the tree:
a clone renamed, moved, or never made. Re-derive rather than trusting a reading
of this file. From the corpus root:

```
find . -maxdepth 5 -name .git -prune | sed 's#/\.git$##' | sort
```

Every path that command prints, in the trees this file lists, should appear
above; every path above should appear in that output. A path in one and not the
other is the finding. Rows are compared against the clone's own `git remote
get-url origin` and `git rev-parse --abbrev-ref HEAD`, not against memory.
