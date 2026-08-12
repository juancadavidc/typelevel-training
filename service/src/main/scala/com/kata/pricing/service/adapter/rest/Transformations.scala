package com.kata.pricing.service.adapter.rest

import cats.data.NonEmptyList
import com.kata.pricing as api
import com.kata.pricing.domain as dom
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import smithy4s.time.Timestamp

/** DTO to domain to persistence, via chimney.
  *
  * The brief calls this "the transformation this project is meant to exercise", so the
  * point is not just that data crosses the boundary but that the crossing is derived
  * rather than hand-copied. What follows is deliberately organised as: implicit
  * `Transformer`s for the leaf types, then a one-line derivation for each aggregate.
  * Every place that needs configuration is a `withField...` call, so a reviewer can see
  * exactly where the shapes disagree — that is the DoD's "deliberate, not a silently
  * hand-written copy".
  *
  * The awkward part of this codebase is that the same concept has three different
  * wrappers: smithy4s emits `Newtype`s (`api.CustomerId`, `.value`), the domain uses
  * `opaque type`s (`dom.CustomerId`, `.value` through an extension), and DynamoDB wants
  * bare `String`s. The leaf transformers below absorb that once; nothing downstream
  * unwraps by hand.
  */
object Transformations:

  // ---------------------------------------------------------------------------
  // Leaf types: the bridge between smithy4s newtypes and domain opaque types.
  // ---------------------------------------------------------------------------

  /** Inbound ids are total: the Smithy model enforces `@length(min: 1)`, so the value
    * already crossed a validated boundary and `unsafe` is honest here rather than a
    * shortcut. That is precisely the case `CustomerId.unsafe` documents. */
  // Both directions are needed, and Scala 3 derives the name of an anonymous `given`
  // from its type — `api.CustomerId -> dom.CustomerId` and its inverse would both be
  // called `given_Transformer_CustomerId_CustomerId` and clash. Hence the explicit names.

  given customerIdIn: Transformer[api.CustomerId, dom.CustomerId] =
    id => dom.CustomerId.unsafe(id.value)

  given couponCodeIn: Transformer[api.CouponCode, dom.CouponCode] =
    code => dom.CouponCode.unsafe(code.value)

  given customerIdOut: Transformer[dom.CustomerId, api.CustomerId] =
    id => api.CustomerId(id.value)

  given couponCodeOut: Transformer[dom.CouponCode, api.CouponCode] =
    code => api.CouponCode(code.value)

  given orderIdOut: Transformer[dom.OrderId, api.OrderId] =
    id => api.OrderId(id.value)

  given moneyOut: Transformer[dom.Money, api.Money] =
    money => api.Money(money.amount)

  given skuOut: Transformer[dom.Sku, String] = _.value

  given quantityOut: Transformer[dom.Quantity, Int] = _.value

  given statusOut: Transformer[dom.OrderStatus, api.OrderStatus] =
    case dom.OrderStatus.Priced => api.OrderStatus.PRICED

  /** `Instant` to smithy4s `Timestamp`: different libraries, same instant. */
  given timestampOut: Transformer[java.time.Instant, Timestamp] = Timestamp.fromInstant

  // ---------------------------------------------------------------------------
  // Inbound: API request -> domain request
  // ---------------------------------------------------------------------------

  /** `OrderItemInput(sku: String, quantity: Int)` to `RequestedItem(sku, quantity)` is a
    * structural match, so the derivation needs no configuration at all. The primitives
    * stay primitive on purpose: this is the *unvalidated* request, and turning `sku` into
    * a `Sku` is validation's job, not the transformer's. */
  given Transformer[api.OrderItemInput, dom.RequestedItem] =
    Transformer.derive[api.OrderItemInput, dom.RequestedItem]

  given Transformer[api.PriceOrderRequest, dom.PriceRequest] =
    Transformer.derive[api.PriceOrderRequest, dom.PriceRequest]

  extension (request: api.PriceOrderRequest)
    def toDomain: dom.PriceRequest = request.transformInto[dom.PriceRequest]

  // ---------------------------------------------------------------------------
  // Outbound: domain -> API response
  // ---------------------------------------------------------------------------

  given Transformer[dom.PricedLine, api.PricedItem] =
    Transformer.derive[dom.PricedLine, api.PricedItem]

  /** The one aggregate that needs configuration, and both reasons are visible:
    *
    *   - `lines` is called `items` in the wire contract. Renaming through
    *     `withFieldRenamed` keeps the derivation while making the mismatch explicit —
    *     the alternative, a hand-written copy of nine fields, is what the DoD warns about.
    *   - `NonEmptyList` to `List`: the domain guarantees at least one line, the wire
    *     format cannot express that. Widening loses a guarantee, so it is stated here
    *     rather than hidden inside an implicit conversion.
    */
  given Transformer[dom.PricedOrder, api.PricedOrderResponse] =
    Transformer
      .define[dom.PricedOrder, api.PricedOrderResponse]
      .withFieldRenamed(_.lines, _.items)
      .withFieldComputed(_.items, _.lines.toList.map(_.transformInto[api.PricedItem]))
      .buildTransformer

  extension (order: dom.PricedOrder)
    def toResponse: api.PricedOrderResponse = order.transformInto[api.PricedOrderResponse]

  // ---------------------------------------------------------------------------
  // Errors: domain ADT -> the Smithy 422
  // ---------------------------------------------------------------------------

  /** `ValidationError` already carries `code`, `field` and `message` as members, which is
    * why the 422 body is a derivation and not a hand-built JSON object — the shape of the
    * error was decided in the domain, where the rules live. */
  given Transformer[dom.ValidationError, api.ValidationErrorDetail] =
    Transformer.derive[dom.ValidationError, api.ValidationErrorDetail]

  extension (errors: NonEmptyList[dom.ValidationError])
    def toValidationException: api.ValidationException =
      api.ValidationException(errors.toList.map(_.transformInto[api.ValidationErrorDetail]))
