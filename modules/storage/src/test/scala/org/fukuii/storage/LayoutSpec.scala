package org.fukuii.storage

import org.scalatest.flatspec.AnyFlatSpec

class LayoutSpec extends AnyFlatSpec:

  private val layout =
    Layout(RepresentationId("hash-keyed"), Set(NamespaceId("chain-header"), NamespaceId("total-difficulty")))

  "RepresentationId" should "round-trip a label through .label" in
    assert(RepresentationId("hash-keyed").label == "hash-keyed", "the label must survive the wrapper")

  "a Layout" should "expose the representation it was constructed with" in
    assert(layout.representation.label == "hash-keyed", "representation must be readable")

  it should "expose the chain-data namespace set it was constructed with" in
    assert(
      layout.chainDataNamespaces == Set(NamespaceId("chain-header"), NamespaceId("total-difficulty")),
      "the namespace set must be exact"
    )
