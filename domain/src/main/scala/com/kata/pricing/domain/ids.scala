package com.kata.pricing.domain

import scala.math.BigDecimal.RoundingMode

/** Domain identifiers and values as `opaque type`s.
  *
  * Why opaque and not `type X = String`: a transparent alias protects nothing — the
  * compiler would happily accept a `CouponCode` where a `CustomerId` is expected. And
  * why not a `case class`: wrapping allocates one object per id. `opaque type` gives
  * compile-time protection at zero runtime cost.
  *
  * The constructors return `Either` instead of throwing: building an invalid value must
  * be impossible, not an exception someone forgets to catch. This is "parse, don't
  * validate": once you hold a `Sku`, you never have to re-check that it is non-empty.
  */

opaque type CustomerId = String

object CustomerId:
  def from(raw: String): Either[String, CustomerId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("customerId must not be blank") else Right(trimmed)

  /** Only for data that already crossed a validated boundary (e.g. the Smithy path,
    * which enforces `@length(min: 1)`) and for test fixtures. */
  def unsafe(raw: String): CustomerId = raw

  extension (id: CustomerId) def value: String = id

opaque type CouponCode = String

object CouponCode:
  def from(raw: String): Either[String, CouponCode] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("couponCode must not be blank") else Right(trimmed)

  def unsafe(raw: String): CouponCode = raw

  extension (code: CouponCode) def value: String = code

opaque type OrderId = String

object OrderId:
  def from(raw: String): Either[String, OrderId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("orderId must not be blank") else Right(trimmed)

  def unsafe(raw: String): OrderId = raw

  extension (id: OrderId) def value: String = id

/** Correlation id for one request. It lives in the domain only because the outbound
  * partner call has to carry it: see `LoyaltyClient.checkPerk`. Everything else about
  * tracing (spans, exporters) stays in the service layer with natchez. */
opaque type TraceId = String

object TraceId:
  def from(raw: String): Either[String, TraceId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("traceId must not be blank") else Right(trimmed)

  def unsafe(raw: String): TraceId = raw

  extension (id: TraceId) def value: String = id

opaque type Sku = String

object Sku:
  def from(raw: String): Either[String, Sku] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("sku must not be blank") else Right(trimmed)

  def unsafe(raw: String): Sku = raw

  extension (sku: Sku) def value: String = sku

/** A strictly positive quantity. Having the type guarantee it removes the need to
  * defend against a zero or a negative in every downstream calculation. */
opaque type Quantity = Int

object Quantity:
  def from(raw: Int): Either[String, Quantity] =
    if raw <= 0 then Left(s"quantity must be greater than zero, got $raw") else Right(raw)

  def unsafe(raw: Int): Quantity = raw

  extension (q: Quantity) def value: Int = q

/** A percentage bounded to 0..100. Bounding it in the constructor is what later lets us
  * show, with no further checks, that a discount never exceeds the total. */
opaque type Percent = Int

object Percent:
  def from(raw: Int): Either[String, Percent] =
    if raw < 0 || raw > 100 then Left(s"percent must be within 0..100, got $raw") else Right(raw)

  def unsafe(raw: Int): Percent = raw

  extension (p: Percent) def value: Int = p

/** Money as a `BigDecimal` with a fixed scale of 2 and HALF_UP rounding.
  *
  * Never `Double`: 10% of 89.97 is not exactly 8.997 in binary floating point, and the
  * ScalaCheck property tests surface that as failures that look like logic bugs.
  * Centralising rounding here also stops every calculation from picking its own, which
  * is how one-cent discrepancies between the subtotal and the sum of lines appear.
  */
opaque type Money = BigDecimal

object Money:
  private val Scale = 2

  private def normalise(raw: BigDecimal): BigDecimal = raw.setScale(Scale, RoundingMode.HALF_UP)

  val zero: Money = normalise(BigDecimal(0))

  def apply(raw: BigDecimal): Money = normalise(raw)

  def from(raw: BigDecimal): Either[String, Money] =
    if raw < 0 then Left(s"money must not be negative, got $raw") else Right(normalise(raw))

  extension (money: Money)
    def amount: BigDecimal = money
    infix def plus(other: Money): Money = Money(money.amount + other.amount)
    /** Subtraction floored at zero: a total can never be negative, and expressing that
      * here is stronger than trusting no caller ever subtracts too much. */
    infix def minusFloored(other: Money): Money =
      val difference = money.amount - other.amount
      if difference < 0 then Money.zero else Money(difference)
    infix def times(factor: Int): Money = Money(money.amount * factor)
    /** Rounds DOWN, not HALF_UP, and deliberately so.
      *
      * The brief pins the result: 10% of 89.97 must yield `discountAmount: 8.99` and
      * `total: 80.98`. The exact value is 8.997, which HALF_UP would turn into 9.00,
      * breaking the contract's example. Rounding the discount down is also the usual
      * commercial convention: never give away fractions of a cent.
      *
      * `Percent.value(percent)` is written in prefix form because `Money` sits outside
      * `Percent`'s scope: there it is no longer an `Int` and its accessor only reaches
      * us through the companion. That is opaque-type protection seen from the inside.
      */
    infix def percentOf(percent: Percent): Money =
      (money.amount * Percent.value(percent) / 100).setScale(Scale, RoundingMode.DOWN)
    infix def isAtLeast(other: Money): Boolean = money.amount >= other.amount

  given Ordering[Money] = Ordering.by(_.amount)
