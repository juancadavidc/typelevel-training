package com.kata.pricing.domain

import java.security.MessageDigest
import java.time.Instant

/** The event published when an order has been priced.
  *
  * It carries the complete resulting state, not a delta. That is deliberate: an event
  * that describes "what is now true" can be applied twice with the same outcome, whereas
  * "subtract 3.99" cannot. Combined with the deterministic `eventId` below, this is what
  * makes at-least-once delivery survivable without a deduplication table.
  */
final case class OrderPricedEvent(
    eventId: String,
    orderId: OrderId,
    customerId: CustomerId,
    subtotal: Money,
    discountAmount: Money,
    total: Money,
    couponApplied: Option[CouponCode],
    createdAt: Instant
)

object OrderPricedEvent:

  /** The idempotency key, derived rather than generated.
    *
    * A random UUID here would make every reprocessing look like a new event, which is
    * exactly the failure DynamoDB Streams' at-least-once delivery guarantees will find:
    * a retried batch would double-count. Deriving the id from the order's identity means
    * reprocessing produces a byte-identical event and the consumer can discard it.
    *
    * Keyed on `orderId` + `createdAt` only. `PricedOrder` has no version field and
    * `OrderStatus` has a single case, `Priced` — an order is priced once. If re-pricing
    * is ever added, this function must take the new version field, or two distinct
    * prices for one order would collapse onto one id. `OrderPricedEventSuite` guards it.
    *
    * The hash input is length-prefixed to prevent collisions: `orderId` is an unvalidated
    * string (any non-blank string is accepted), so two distinct (orderId, createdAt) pairs
    * could produce the same naive concatenation — e.g., "a|b" + "c" vs. "a" + "|bc". A
    * collision here means the consumer's deduplication drops one order. Length-prefixing
    * guarantees the encoding is unambiguous.
    */
  def eventIdFor(orderId: OrderId, createdAt: Instant): String =
    val payload = s"${orderId.value.length}:${orderId.value}|${createdAt.toString}"
    val digest  = MessageDigest.getInstance("SHA-256").digest(payload.getBytes("UTF-8"))
    digest.map(byte => f"$byte%02x").mkString

  def from(order: PricedOrder): OrderPricedEvent =
    OrderPricedEvent(
      eventId = eventIdFor(order.orderId, order.createdAt),
      orderId = order.orderId,
      customerId = order.customerId,
      subtotal = order.subtotal,
      discountAmount = order.discountAmount,
      total = order.total,
      couponApplied = order.couponApplied,
      createdAt = order.createdAt
    )

  /** Kinesis orders records within a shard, not across them. Keying by `orderId` puts
    * every event for one order on the same shard, so a consumer sees that order's
    * history in order — which is the guarantee that actually matters here.
    */
  extension (event: OrderPricedEvent) def partitionKey: String = event.orderId.value
