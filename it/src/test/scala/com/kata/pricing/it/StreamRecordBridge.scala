package com.kata.pricing.it

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{
  AttributeValue as V1AttributeValue,
  StreamRecord as V1StreamRecord
}
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue as V2AttributeValue,
  Record as V2Record
}

import scala.jdk.CollectionConverters.*

/** Test code standing in for the AWS Lambda runtime.
  *
  * In production nothing performs this conversion: the runtime hands the handler
  * `DynamodbStreamRecord` POJOs directly. The suite, however, reads the stream itself with
  * the v2 SDK — which returns `software.amazon.awssdk...Record`, an unrelated class — so
  * something has to bridge the two before `StreamProcessor.process` can be called.
  *
  * That makes this the one genuinely dangerous file in the phase. It sits exactly where
  * the suite could lie: if this converter knew the domain's attribute names, it could
  * "fix" a mismatch between what `OrderRepoDynamo` writes and what `StreamDecoder` reads,
  * and the integration test would go green over a system that is broken in production.
  * The whole point of the suite is to catch that mismatch.
  *
  * ==The rule==
  *
  * '''This bridge is purely structural.''' It copies `eventName`, `sequenceNumber` and the
  * NEW_IMAGE map attribute by attribute, and it knows no domain field names. If the string
  * `"orderId"` — or `"subtotal"`, or any other attribute name — ever appears in this file,
  * it is written wrong: it would mean the bridge is interpreting the payload instead of
  * transporting it.
  *
  * Stated here rather than only in the design document, because a rule that lives only in
  * a design document is not a rule.
  *
  * Nulls are preserved rather than defaulted, for the same reason: `StreamProcessor` and
  * `StreamDecoder` both have explicit null-handling paths (a record with no `eventName`, a
  * null `dynamodb` payload), and a bridge that quietly substituted empty values would hide
  * the very cases those paths exist for.
  */
object StreamRecordBridge:

  def toLambdaRecord(record: V2Record): DynamodbStreamRecord =
    val bridged = new DynamodbStreamRecord()
    bridged.setEventName(record.eventNameAsString())

    Option(record.dynamodb()).foreach { stream =>
      val v1Stream = new V1StreamRecord()
      v1Stream.setSequenceNumber(stream.sequenceNumber())
      if stream.hasNewImage then v1Stream.setNewImage(toV1Image(stream.newImage()))
      if stream.hasKeys then v1Stream.setKeys(toV1Image(stream.keys()))
      bridged.setDynamodb(v1Stream)
    }

    bridged

  private def toV1Image(
      image: java.util.Map[String, V2AttributeValue]
  ): java.util.Map[String, V1AttributeValue] =
    image.asScala.map { case (name, value) => name -> toV1Attribute(value) }.asJava

  /** Type-by-type, with no knowledge of which attribute is being converted.
    *
    * Only the shapes this system actually writes are handled (`S`, `N`, `M`, `L`, `BOOL`,
    * `NULL`). An unhandled type yields an empty `AttributeValue` rather than throwing:
    * this stands in for a runtime, and a runtime that crashed on an unfamiliar attribute
    * would fail the suite for a reason that has nothing to do with the code under test.
    */
  private def toV1Attribute(value: V2AttributeValue): V1AttributeValue =
    val converted = new V1AttributeValue()

    if value.s() != null then converted.setS(value.s())
    else if value.n() != null then converted.setN(value.n())
    else if value.bool() != null then converted.setBOOL(value.bool())
    else if value.nul() != null then converted.setNULL(value.nul())
    else if value.hasM then converted.setM(toV1Image(value.m()))
    else if value.hasL then converted.setL(value.l().asScala.map(toV1Attribute).asJava)

    converted
