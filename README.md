# 🧠🪱 Fukuii

**An independent, ground-up Scala execution client for the EVM ecosystem — designed to be Ethereum Classic's first native client.**

## Origin

*Chordodes fukuii* is a horsehair worm that infects a mantis, takes over its nervous system, drives it toward water, and emerges as something else entirely — leaving the mantis behind. The name is the lifecycle, and it's this project's too.

Fukuii owes two predecessors, and takes source code from neither. IOHK's Mantis chose Scala and the JVM for an Ethereum client; ETCDEV's Orbita (2018) was an early vision for a multi-network Ethereum Classic client. Fukuii inherits the tech-stack choice and the multi-network ambition without inheriting any code — it is an independent, ground-up implementation, and is not a derivative work of Mantis. "Mantis" is a trademark of IOHK, named here only for the lineage; see [`NOTICE`](NOTICE).

It's written to be Ethereum Classic's first *native* client: not an existing Ethereum client forked and adapted to run ETC, but one designed from its first line to run several networks — Ethereum Classic among them — across more than one consensus model, from one binary.

## What Fukuii is

The design is one binary, one network-family framework. Rather than a new client per chain, a further network — its chain ID, genesis, fork schedule, gas and fee mechanics — is meant to be configuration layered onto a shared execution engine, so the same build is meant to run more than one production network, in more than one consensus model, from a single JVM process. The same binary is also meant to fill the infrastructure roles a network depends on — bootnode, RPC relay, faucet — rather than only consuming them from someone else.

## Why

A node checks every block against the protocol rules before accepting it; a public RPC endpoint gives you whatever answer it has, with no way to check it. Mining and staking both need a node, and so does serving RPC to someone else or holding the history other nodes bootstrap from.

Ethereum Classic's clients, so far, have been Ethereum clients adapted to run it. Fukuii is written to be the first that wasn't — designed from the start for an EVM ecosystem that already spans more than one consensus model, rather than retrofitting that reality onto a client that was never built to expect it.

## How

The design separates execution from consensus. An execution engine — bytecode, state trie, transaction validation, block processing, JSON-RPC — is meant to hold no chain-specific behavior and no consensus logic; a chain's identity is a parameter layered on top of it; and consensus itself is a pluggable module behind the Engine API, so ETChash, a Proof-of-Stake consensus layer, or a permissioned driver like QBFT are all meant to satisfy the same seam. Adding a network is meant to be a definition, not a fork of the client.

Each configured network is meant to run as its own actor supervision tree — its own state, its own metrics, its own failure domain — so one process can host several networks without a fault in one taking the others down. Scala 3's algebraic data types and exhaustive pattern matching are there to turn a missed case in consensus-critical code into a compile failure instead of a shipped bug. The JVM is the target runtime because a node is a long-lived process, and JFR, async-profiler, JMX and heap dumps come for free rather than needing to be built.

`modules/` is the layering's intended home in the source tree — see **Status** below for what exists today.

## Status

No application code exists in this repository yet. What's here is a pinned toolchain and the scaffolding it will build on:

- A minimal `build.sbt` declaring only what's settled — the Scala and JDK versions, and the ScalaTest style artifacts the testing policy commits to. [`AGENTS.md`](AGENTS.md) has the full stack table, the setup commands, and the reasoning behind each pin.
- No `modules/` tree yet. It is where the layering above will live, and a clone does not receive it — git does not track empty directories, so it arrives with the first module that has a file in it.
- `src/test/`, one toolchain-proof spec per declared ScalaTest style artifact, proving the pinned test toolchain resolves and actually reports a result. They're retired the day a real spec exists to replace them.

Apache Pekko is decided as the actor-system dependency and isn't declared in the build yet — nothing gets added before something needs it.

## Project

fukuii-cli is one repository in the [Fukuii project](https://github.com/fukuii-project). [`fukuii-tests`](https://github.com/fukuii-project/fukuii-tests) holds the consensus and conformance corpus this client will be checked against, [`fukuii-gui`](https://github.com/fukuii-project/fukuii-gui) is a graphical interface for it, and [`fukuii-org`](https://github.com/fukuii-project/fukuii-org) builds [fukuii.org](https://fukuii.org), where the fuller story lives.

This is currently a single-developer project: work happens on local branches merged into `main`, or directly on `main`. The fork-and-pull-request workflow the organization's [contributing guidelines](https://github.com/fukuii-project/.github/blob/main/CONTRIBUTING.md) describe applies once the project opens to outside contributors.

Report a suspected security vulnerability through [this repository's private advisory form](https://github.com/fukuii-project/fukuii-cli/security/advisories/new), not the public issue tracker.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE), with copyright holders named in [`NOTICE`](NOTICE).
