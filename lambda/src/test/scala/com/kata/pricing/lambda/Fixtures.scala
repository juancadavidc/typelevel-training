package com.kata.pricing.lambda

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.{AttributeValue, StreamRecord}
import com.kata.pricing.domain.OrderPricedEvent
import com.kata.pricing.lambda.port.KinesisPublisher

import scala.jdk.CollectionConverters.*

/** Builders for the Java event types AWS hands the handler. They are mutable POJOs, so
  * construction is unavoidably imperative — it is confined here rather than spread
  * across the suites.
  */
object Fixtures:

  private def s(value: String): AttributeValue = new AttributeValue().withS(value)
  private def n(value: String): AttributeValue = new AttributeValue().withN(value)

  /** Mirrors exactly the attribute map written by
    * `service/.../dynamo/OrderRepoDynamo.scala`. Keep the two in step.
    *
    * This is a hand-copy, not a shared constant: `lambda` cannot depend on `service`
    * (that would drag the whole HTTP/DynamoDB write path onto a Lambda's classpath just
    * to reuse a map-building helper), so nothing stops this literally drifting from the
    * writer if someone renames or adds an attribute there and forgets this file. The
    * closest thing to a safety net is `StreamDecoderSuite`'s "matches what
    * OrderPricedEvent.from produces directly" test, which at least proves this fixture's
    * *shape* decodes to the same event the domain layer would build straight from a
    * `PricedOrder` — it does not, and cannot, prove the fixture matches the real writer.
    */
  def newImage(
      orderId: String,
      createdAt: String,
      coupon: Option[String] = Some("SUMMER10")
  ): java.util.Map[String, AttributeValue] =
    val items = new AttributeValue().withL(
      new AttributeValue().withM(
        Map(
          "sku"       -> s("SKU-001"),
          "quantity"  -> n("2"),
          "unitPrice" -> n("19.99"),
          "lineTotal" -> n("39.98")
        ).asJava
      )
    )

    val base = Map(
      "orderId"        -> s(orderId),
      "customerId"     -> s("cust-123"),
      "status"         -> s("PRICED"),
      "items"          -> items,
      "subtotal"       -> n("39.98"),
      "discountAmount" -> n("3.99"),
      "total"          -> n("35.99"),
      "createdAt"      -> s(createdAt),
      "updatedAt"      -> s(createdAt)
    ) ++ coupon.map(code => "couponCode" -> s(code))

    new java.util.HashMap(base.asJava)

  def insertRecord(
      orderId: String,
      createdAt: String = "2026-07-22T14:32:00Z",
      sequenceNumber: String = "seq-1",
      coupon: Option[String] = Some("SUMMER10")
  ): DynamodbStreamRecord =
    val stream = new StreamRecord()
    stream.setNewImage(newImage(orderId, createdAt, coupon))
    stream.setSequenceNumber(sequenceNumber)

    val record = new DynamodbStreamRecord()
    record.setEventName("INSERT")
    record.setDynamodb(stream)
    record

  /** A record AWS itself can hand us: an `eventName` with no `dynamodb` payload at all
    * (null, not merely empty). `insertRecord` cannot express this — it always attaches a
    * `StreamRecord` — so this exists to pin the crash `failuresFrom` used to hit when it
    * dereferenced `getDynamodb` unguarded. */
  def malformedRecord(eventName: String = "INSERT"): DynamodbStreamRecord =
    val record = new DynamodbStreamRecord()
    record.setEventName(eventName)
    record

  /** A record with a `dynamodb` payload but no `sequenceNumber` on it — the other half
    * of the null-guard `failuresFrom` needs, independent of `getDynamodb` itself. */
  def recordWithoutSequenceNumber(
      orderId: String,
      createdAt: String = "2026-07-22T14:32:00Z"
  ): DynamodbStreamRecord =
    val stream = new StreamRecord()
    stream.setNewImage(newImage(orderId, createdAt))

    val record = new DynamodbStreamRecord()
    record.setEventName("INSERT")
    record.setDynamodb(stream)
    record

  def removeRecord(orderId: String, sequenceNumber: String = "seq-x"): DynamodbStreamRecord =
    val stream = new StreamRecord()
    stream.setSequenceNumber(sequenceNumber)
    stream.setKeys(Map("orderId" -> s(orderId)).asJava)

    val record = new DynamodbStreamRecord()
    record.setEventName("REMOVE")
    record.setDynamodb(stream)
    record

  def event(records: DynamodbStreamRecord*): DynamodbEvent =
    val e = new DynamodbEvent()
    e.setRecords(records.toList.asJava)
    e

  /** An in-memory publisher: a `Ref`, not a mocking framework. Returns the publisher and
    * a way to read what it received. */
  def recordingPublisher[F[_]: Sync]: F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])] =
    Ref[F].of(Vector.empty[OrderPricedEvent]).map { ref =>
      val publisher = new KinesisPublisher[F]:
        def publish(event: OrderPricedEvent): F[Unit] = ref.update(_ :+ event)
      (publisher, ref.get)
    }

  /** A publisher that fails for one named record and records every other event.
    *
    * Keyed on `orderId.value` rather than a completion count: under `parEvalMap` with
    * `concurrency > 1`, publishes race, so "the Nth to complete" does not mean "the Nth
    * record" — whichever call happens to reach a shared counter second gets the error,
    * regardless of which record it belongs to. That was the previous fixture's bug:
    * `failOn = 2` could inject the failure into `order-1` instead of `order-2` if
    * `order-2`'s publish happened to finish first, silently proving a weaker claim than
    * the test's assertion implied. Identity is race-proof because it depends only on
    * the event's own field, never on timing relative to any other publish.
    */
  def failingPublisherFor[F[_]: Sync](
      failOrderId: String,
      error: Throwable
  ): F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])] =
    Ref[F].of(Vector.empty[OrderPricedEvent]).map { ref =>
      val publisher = new KinesisPublisher[F]:
        def publish(event: OrderPricedEvent): F[Unit] =
          if event.orderId.value == failOrderId then Sync[F].raiseError(error)
          else ref.update(_ :+ event)
      (publisher, ref.get)
    }
