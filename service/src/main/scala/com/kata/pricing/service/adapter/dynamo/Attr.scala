package com.kata.pricing.service.adapter.dynamo

import cats.syntax.all.*
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import java.time.Instant

/** Reading and writing DynamoDB's `AttributeValue`, in one place.
  *
  * The accessors return `Option` rather than throwing because the SDK's own do the
  * opposite: `AttributeValue.n()` on a missing or wrong-typed attribute throws, and a
  * malformed row must not take down a request. Every adapter in this package decodes
  * through these, so "unreadable item" consistently reads as absent.
  */
private[dynamo] object Attr:
  def s(value: String): AttributeValue = AttributeValue.fromS(value)
  def n(value: String): AttributeValue = AttributeValue.fromN(value)

  extension (item: Map[String, AttributeValue])
    def str(key: String): Option[String] = item.get(key).map(_.s())

    def num(key: String): Option[BigDecimal] =
      item.get(key).flatMap(value => Either.catchNonFatal(BigDecimal(value.n())).toOption)

    def int(key: String): Option[Int] = item.num(key).map(_.toInt)

    def instant(key: String): Option[Instant] =
      item.str(key).flatMap(raw => Either.catchNonFatal(Instant.parse(raw)).toOption)
