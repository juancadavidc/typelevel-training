package com.kata.pricing.it

import weaver.*

import scala.io.Source
import scala.util.Using

/** The rule that keeps the bridge honest, enforced instead of merely documented.
  *
  * `StreamRecordBridge` stands in for the AWS Lambda runtime, and that makes it the one
  * piece of this phase that could quietly invalidate the whole suite: if it knew the
  * domain's attribute names, it could paper over a mismatch between what `OrderRepoDynamo`
  * writes and what `StreamDecoder` reads — turning the integration suite green over a
  * system that is broken in production. Since catching exactly that mismatch is the reason
  * this module exists, the bridge has to stay purely structural.
  *
  * Its scaladoc says so. This test is what makes that more than a good intention: a rule
  * enforced only by a comment survives precisely until the first person in a hurry.
  *
  * Reading the source file is unusual for a unit test and deliberate here. The property
  * being asserted — "this code contains no knowledge of the domain" — is a property of the
  * text, not of any value the code produces. There is nothing to call that would reveal
  * it.
  */
object StreamRecordBridgeSuite extends FunSuite:

  private val source =
    Using.resource(
      Source.fromFile("it/src/test/scala/com/kata/pricing/it/StreamRecordBridge.scala")
    )(_.getLines().toList)

  /** Every attribute `OrderRepoDynamo` writes and `StreamDecoder` reads. If the bridge
    * mentions any of them, it is interpreting the payload rather than transporting it. */
  private val domainAttributes = List(
    "orderId",
    "customerId",
    "status",
    "items",
    "subtotal",
    "discountAmount",
    "total",
    "createdAt",
    "updatedAt",
    "couponCode"
  )

  /** Scaladoc is exempt: the rule itself has to be able to name what it forbids. Only
    * executable lines are checked. */
  private val code: List[String] =
    source
      .map(_.trim)
      .filterNot(line => line.startsWith("*") || line.startsWith("/*") || line.startsWith("//"))

  test("the bridge names no domain attribute") {
    val offenders = for
      attribute <- domainAttributes
      line      <- code
      if line.contains(s""""$attribute"""")
    yield s"$attribute — in: $line"

    expect(
      offenders.isEmpty,
      "StreamRecordBridge must copy the NEW_IMAGE attribute by attribute without knowing " +
        "any domain field name; a bridge that names a field can hide a producer/consumer " +
        s"mismatch the suite exists to catch. Found: ${offenders.mkString("; ")}"
    )
  }

  /** The stronger form of the same rule, and the reason it is worth two assertions: the
    * bridge should contain no string literals *at all* in its executable code. Any literal
    * is either a field name or a default value, and both mean it has started making
    * decisions about content. */
  test("the bridge contains no string literals at all") {
    val literals = code.flatMap(line => """"[^"]*"""".r.findAllIn(line).toList)

    expect(
      literals.isEmpty,
      "StreamRecordBridge should be purely structural: no string literal belongs in it, " +
        s"since every one would be either a field name or a substituted value. Found: ${literals.mkString(", ")}"
    )
  }
