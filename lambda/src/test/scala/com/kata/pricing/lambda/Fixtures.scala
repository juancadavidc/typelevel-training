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
    * `service/.../dynamo/OrderRepoDynamo.scala`. Keep the two in step. */
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
