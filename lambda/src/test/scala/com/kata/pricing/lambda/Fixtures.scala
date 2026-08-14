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

  /** A publisher that fails on the nth event (1-based) and records the rest. */
  def failingPublisher[F[_]: Sync](
      failOn: Int,
      error: Throwable
  ): F[(KinesisPublisher[F], F[Vector[OrderPricedEvent]])] =
    Ref[F].of(Vector.empty[OrderPricedEvent]).map { ref =>
      val publisher = new KinesisPublisher[F]:
        def publish(event: OrderPricedEvent): F[Unit] =
          ref.updateAndGet(_ :+ event).flatMap { seen =>
            Sync[F].raiseError(error).whenA(seen.size == failOn)
          }
      (publisher, ref.get)
    }
