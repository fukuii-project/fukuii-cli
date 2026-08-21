package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

/** That both facets answer by VALUE, which is the property a later member could
  * silently take away.
  *
  * ==Why this is worth a spec when the members are booleans==
  *
  * Because the question these records exist inside is *do these two networks
  * run the same rules*, and a record answers it by value only while every one
  * of its members does. A member added later as a function, or as an open
  * interface with a default implementation, would not fail to compile and would
  * not fail any test that builds one value and reads it back -- it would make
  * two identical configurations built separately compare unequal, so the answer
  * would start depending on how a caller happened to construct its inputs.
  *
  * Both directions are asserted for each record. A comparison that reported
  * equal unconditionally would satisfy the first half alone.
  */
class RuleFacetSpec extends AnyFlatSpec:

  private val execution: ExecutionRules =
    ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false)

  private val admission: AdmissionRules = AdmissionRules(signatureSMustBeLow = false)

  "two execution rule sets" should "compare equal when they were built separately from the same parts" in
    assert(
      ExecutionRules(touchedEmptyAccountsAreDeleted = false, receiptCarriesStatus = false) == execution,
      "two identical settlement rule sets built separately compared as different rules"
    )

  it should "compare unequal when a single rule differs" in
    assert(
      execution.copy(receiptCarriesStatus = true) != execution,
      "a settlement rule set compared equal to one that settles a receipt differently"
    )

  "two admission rule sets" should "compare equal when they were built separately from the same parts" in
    assert(
      AdmissionRules(signatureSMustBeLow = false) == admission,
      "two identical admission rule sets built separately compared as different rules"
    )

  it should "compare unequal when a single rule differs" in
    assert(
      admission.copy(signatureSMustBeLow = true) != admission,
      "an admission rule set compared equal to one that refuses a signature it accepts"
    )
