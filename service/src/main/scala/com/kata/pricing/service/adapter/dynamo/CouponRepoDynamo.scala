package com.kata.pricing.service.adapter.dynamo

import cats.effect.Async
import cats.syntax.all.*
import com.kata.pricing.domain.{Coupon, CouponCode, Money, Percent, Tier}
import com.kata.pricing.domain.port.CouponRepo
import natchez.Trace
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest}

import scala.jdk.CollectionConverters.*

import Attr.*

/** The `CouponRepo` port, driven by DynamoDB. */
final class CouponRepoDynamo[F[_]: Async: Trace](
    client: DynamoDbAsyncClient,
    tableName: String
) extends CouponRepo[F]:

  def find(code: CouponCode): F[Option[Coupon]] =
    Trace[F].span("dynamodb.get-coupon") {
      Trace[F].put("db.table" -> tableName, "pricing.coupon" -> code.value) *> fetch(code)
    }

  private def fetch(code: CouponCode): F[Option[Coupon]] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("couponCode" -> Attr.s(code.value)).asJava)
      .build()

    Async[F]
      .fromCompletableFuture(Async[F].delay(client.getItem(request)))
      .map(response => if response.hasItem then decode(code, response.item().asScala.toMap) else None)

  /** A malformed row reads as absent. `CouponRepo` returns `F[Option[_]]`, and a coupon
    * that cannot be parsed has the same business outcome as one that does not exist —
    * validation turns it into `COUPON_NOT_FOUND`. */
  private def decode(code: CouponCode, item: Map[String, AttributeValue]): Option[Coupon] =
    for
      percentRaw <- item.int("discountPercent")
      percent    <- Percent.from(percentRaw).toOption
      minimumRaw <- item.num("minOrderAmount")
      minimum    <- Money.from(minimumRaw).toOption
      usageLimit <- item.int("usageLimit")
      usageCount <- item.int("usageCount")
      expiresAt  <- item.instant("expiresAt")
    yield Coupon(
      code = code,
      discountPercent = percent,
      minOrderAmount = minimum,
      usageLimit = usageLimit,
      usageCount = usageCount,
      expiresAt = expiresAt,
      // The brief allows a boolean or a list of tiers; a list is stored, and an absent
      // attribute means "stackable with none" rather than "with all" — the safe default.
      stackableWithTiers = item
        .get("stackableWithTiers")
        .toList
        .flatMap(_.l().asScala.toList)
        .flatMap(value => Tier.from(value.s()).toOption)
        .toSet
    )
