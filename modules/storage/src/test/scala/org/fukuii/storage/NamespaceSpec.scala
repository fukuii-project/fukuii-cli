package org.fukuii.storage

import org.scalatest.flatspec.AnyFlatSpec

class NamespaceSpec extends AnyFlatSpec:

  "NamespaceId" should "round-trip a label through .label" in
    assert(NamespaceId("chain-header").label == "chain-header", "the label must survive the wrapper")

  "a Namespace.Standalone" should "expose the id it was constructed with" in {
    val ns = Namespace.Standalone(NamespaceId("state"), Seam.State, WriteMode.Mutable)
    assert(ns.id.label == "state", "id must be readable through the Namespace extension")
  }

  it should "expose the seam it was constructed with" in {
    val ns = Namespace.Standalone(NamespaceId("state"), Seam.State, WriteMode.Mutable)
    assert(ns.seam == Seam.State, "seam must be readable through the Namespace extension")
  }

  it should "expose the write mode it was constructed with" in {
    val ns = Namespace.Standalone(NamespaceId("state"), Seam.State, WriteMode.Mutable)
    assert(ns.writeMode == WriteMode.Mutable, "writeMode must be readable through the Namespace extension")
  }

  "a Namespace.Coupled" should "expose the id of its own keyspace, not its companion's" in {
    val companion: Namespace.Standalone = Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val coupled: Namespace.Coupled = Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    assert(coupled.id.label == "chain-header", "id must be the coupled namespace's own, not the companion's")
  }

  it should "expose its companion as a Namespace.Standalone" in {
    val companion: Namespace.Standalone = Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val coupled: Namespace.Coupled = Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    assert(coupled.companion == companion, "the companion must be exactly the value supplied at construction")
  }
