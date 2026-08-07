package com.kata.pricing.domain

import scala.math.BigDecimal.RoundingMode

/** Identificadores y valores del dominio como `opaque type`.
  *
  * Por qué opaque y no `type X = String`: un alias transparente no protege de nada —
  * el compilador aceptaría pasar un `CouponCode` donde se espera un `CustomerId`.
  * Y por qué no una `case class`: envolver añade un objeto en memoria por cada id.
  * `opaque type` da la protección en compilación con coste cero en runtime.
  *
  * Los constructores devuelven `Either` en vez de lanzar: construir un valor inválido
  * debe ser imposible, no una excepción que alguien olvide capturar. Es "parse, don't
  * validate": una vez tienes un `Sku`, ya no hay que volver a comprobar que no está vacío.
  */

opaque type CustomerId = String

object CustomerId:
  def from(raw: String): Either[String, CustomerId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("customerId must not be blank") else Right(trimmed)

  /** Sólo para datos que ya cruzaron una frontera validada (p. ej. el path de Smithy,
    * que aplica `@length(min: 1)`) y para fixtures de test. */
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

opaque type Sku = String

object Sku:
  def from(raw: String): Either[String, Sku] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("sku must not be blank") else Right(trimmed)

  def unsafe(raw: String): Sku = raw

  extension (sku: Sku) def value: String = sku

/** Cantidad estrictamente positiva. Que el tipo lo garantice evita tener que
  * defenderse de un cero o un negativo en cada cálculo posterior. */
opaque type Quantity = Int

object Quantity:
  def from(raw: Int): Either[String, Quantity] =
    if raw <= 0 then Left(s"quantity must be greater than zero, got $raw") else Right(raw)

  def unsafe(raw: Int): Quantity = raw

  extension (q: Quantity) def value: Int = q

/** Porcentaje acotado a 0..100. El acotamiento en el constructor es lo que permite
  * demostrar después, sin más comprobaciones, que un descuento nunca supera el total. */
opaque type Percent = Int

object Percent:
  def from(raw: Int): Either[String, Percent] =
    if raw < 0 || raw > 100 then Left(s"percent must be within 0..100, got $raw") else Right(raw)

  def unsafe(raw: Int): Percent = raw

  extension (p: Percent) def value: Int = p

/** Dinero como `BigDecimal` con escala fija de 2 y redondeo HALF_UP.
  *
  * Nunca `Double`: el 10% de 89.97 no es exactamente 8.997 en coma flotante binaria,
  * y los property tests de ScalaCheck lo destapan como fallos que parecen bugs de
  * lógica. Centralizar aquí el redondeo evita además que cada cálculo elija el suyo,
  * que es como aparecen las diferencias de un céntimo entre subtotal y suma de líneas.
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
    /** Resta acotada por cero: un total nunca puede ser negativo, y expresarlo aquí
      * es más fuerte que confiar en que ningún llamador reste de más. */
    infix def minusFloored(other: Money): Money =
      val difference = money.amount - other.amount
      if difference < 0 then Money.zero else Money(difference)
    infix def times(factor: Int): Money = Money(money.amount * factor)
    /** Redondeo DOWN, no HALF_UP, y a propósito.
      *
      * El PDF fija el resultado: 10% de 89.97 debe dar `discountAmount: 8.99` y
      * `total: 80.98`. El valor exacto es 8.997, que con HALF_UP sería 9.00 y rompería
      * el ejemplo del contrato. Redondear el descuento hacia abajo es además la
      * convención comercial habitual: nunca se regalan fracciones de céntimo.
      *
      * `Percent.value(percent)` va en forma prefija porque `Money` está fuera del scope
      * de `Percent`: allí ya no es un `Int` y su accessor sólo llega por el companion.
      * Es la protección de los opaque types vista desde dentro.
      */
    infix def percentOf(percent: Percent): Money =
      (money.amount * Percent.value(percent) / 100).setScale(Scale, RoundingMode.DOWN)
    infix def isAtLeast(other: Money): Boolean = money.amount >= other.amount

  given Ordering[Money] = Ordering.by(_.amount)
