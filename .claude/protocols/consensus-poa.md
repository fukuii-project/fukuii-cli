# Consensus — the proof-of-authority family

**currency:** **this file is thin on purpose and carries almost no protocol values.**
What it does carry — client support, and which mechanism protocols exist — was
measured on 2026-08-19 with the commands each claim names. **The mechanism detail
is NOT here.** `consensus-clique.md`, `consensus-aura.md`, `consensus-qbft.md` and
`consensus-ibft2.md` each hold one mechanism, each written from a production-client
survey, and each states its own evidence weight. Restating any of it here would give
one fact two homes, which is the failure this file is shaped to avoid.

---

## Why this file exists, when four mechanism protocols already did

**A mechanism protocol commits this project to implementing nothing** — that is
stated at the head of each of the four, and it is why they could be written from a
survey without scheduling any work.

**That changed on 2026-08-19.** Private proof-of-authority networks became **stage 3
of the network roadmap**, and nothing recorded that the family had moved from
*surveyed* to *scheduled*. This file records it, and nothing else.

## What this family is for, and it is unlike the other two

**A network fukuii launches itself, in order to exercise work that is not ready for
a public network.** The proof-of-work and proof-of-stake families are networks that
exist and that fukuii must join. This family is the opposite: **fukuii authors the
genesis, chooses the validator set, and owns the chain.**

**Its purpose is to serve stage 2** — an environment to exercise an Ethereum Classic
upgrade before it reaches a public testnet. That makes it a **prerequisite rather
than a target**, and it is the reason it is sequenced ahead of the sidechains, which
buy reach rather than a place to test.

## Membership — authored, not enumerated

The other two families name public networks. **This one cannot**, because its members
do not exist until someone creates them. Membership is *whatever this project stands
up*, and a roster here would be a list of one thing that changes whenever a developer
starts a second network.

## Fork dispatch — expect genesis, and do not assume it

Both other families dispatch on a schedule inherited from a public chain: block number
for proof-of-work, timestamp for proof-of-stake. **An authored network usually
activates everything at genesis instead**, because there is no history to preserve.

**That is an expectation, not a measured fact**, and it is exactly the kind of claim
this project requires be checked against a client's genesis handling before it is
relied upon. It is written as an expectation so that a reader knows to check it.

## The reference clients, and one of them leads twice

| Client | Engines it carries | Measured |
|---|---|---|
| **`besu-eth/besu`** | `consensus/clique`, `consensus/ibft`, `consensus/qbft` | the **most complete** private-network client, and therefore this family's reference — which is the second thing besu is the reference for, the first being JVM shape |
| `NethermindEth/nethermind` | `Consensus.AuRa`, `Consensus.Clique` | the AuRa reference |
| `ethereum/go-ethereum` | `consensus/clique`, plus a `--dev` instant-seal mode | Clique's origin, and the narrowest of the three |

File counts from `git ls-files` on each clone, 2026-08-19.

## The open decision this file does not make

**Which mechanism fukuii uses for its own devnets is unsettled**, and this file
deliberately does not settle it. The four are surveyed; the choice depends on what
the devnet has to demonstrate, and choosing before that is known would be choosing
without a requirement.

**What the choice must not do is foreclose the others.** The seam this project has
already committed to — a chain configuration producing the opcode table, gas schedule
and precompile set as a baseline plus per-proposal deltas — is the same shape a
consensus module needs, and `.claude/agents/forge.md`'s litmus still governs: a change
that alters a state root is consensus, and one that does not is client policy.

## Evidence weight

**Low, and deliberately so.** This file asserts three things: that the family is
scheduled, what it is for, and which clients carry which engines. The first two are
project decisions and cannot be wrong about the world; the third is measured and
dated. **Everything else about proof-of-authority is in the four mechanism protocols**,
and this file is not an index of them — read the directory.
