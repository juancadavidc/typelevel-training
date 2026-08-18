package com.kata.pricing.lambda

import cats.syntax.all.*
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue
import com.kata.pricing.domain.*

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Turns a raw stream record into a domain event, or explains why it cannot.
  *
  * Pure and total: every failure is a `Left`, nothing throws. The three-way return says
  * something the pipeline needs to distinguish — `Right(None)` is "correctly skipped"
  * (a REMOVE, which is not a pricing event), while `Left` is "this should have decoded
  * and did not", which must not pass silently.
  *
  * This is the mirror image of `OrderRepoDynamo.put`. The attribute names are a contract
  * between the two, enforced by a test rather than by hope.
  */
object StreamDecoder:

  private val Priced = Set("INSERT", "MODIFY")

  def decode(record: DynamodbStreamRecord): Either[String, Option[OrderPricedEvent]] =
    Option(record.getEventName) match
      case Some(name) if Priced.contains(name) => decodeImage(record).map(Some(_))
      case Some(_)                             => Right(None)
      case None                                => Left("record has no eventName")

  private def decodeImage(record: DynamodbStreamRecord): Either[String, OrderPricedEvent] =
    for
      stream <- Option(record.getDynamodb).toRight("record has no dynamodb payload")
      image  <- Option(stream.getNewImage).map(_.asScala.toMap).toRight("record has no NEW_IMAGE")
      order  <- toEvent(image)
    yield order

  private def toEvent(
      image: Map[String, AttributeValue]
  ): Either[String, OrderPricedEvent] =
    for
      rawOrderId    <- string(image, "orderId")
      orderId       <- OrderId.from(rawOrderId)
      rawCustomerId <- string(image, "customerId")
      customerId    <- CustomerId.from(rawCustomerId)
      subtotal      <- money(image, "subtotal")
      discount      <- money(image, "discountAmount")
      total         <- money(image, "total")
      createdAt     <- instant(image, "createdAt")
      coupon        <- optionalCoupon(image)
    yield OrderPricedEvent(
      eventId = OrderPricedEvent.eventIdFor(orderId, createdAt),
      orderId = orderId,
      customerId = customerId,
      subtotal = subtotal,
      discountAmount = discount,
      total = total,
      couponApplied = coupon,
      createdAt = createdAt
    )

  private def string(image: Map[String, AttributeValue], key: String): Either[String, String] =
    image.get(key).flatMap(attr => Option(attr.getS)).toRight(s"missing string attribute '$key'")

  private def money(image: Map[String, AttributeValue], key: String): Either[String, Money] =
    for
      raw    <- image.get(key).flatMap(attr => Option(attr.getN)).toRight(s"missing numeric attribute '$key'")
      parsed <- Try(BigDecimal(raw)).toEither.leftMap(_ => s"attribute '$key' is not a number: '$raw'")
      value  <- Money.from(parsed)
    yield value

  private def instant(image: Map[String, AttributeValue], key: String): Either[String, Instant] =
    string(image, key).flatMap { raw =>
      Try(Instant.parse(raw)).toEither.leftMap(_ => s"attribute '$key' is not an instant: '$raw'")
    }

  /** Absent is legitimate — an order without a coupon. Present but malformed is not. */
  private def optionalCoupon(
      image: Map[String, AttributeValue]
  ): Either[String, Option[CouponCode]] =
    image.get("couponCode").flatMap(attr => Option(attr.getS)) match
      case None       => Right(None)
      case Some(code) => CouponCode.from(code).map(Some(_))
