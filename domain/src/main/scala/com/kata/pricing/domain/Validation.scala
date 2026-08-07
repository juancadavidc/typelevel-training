package com.kata.pricing.domain

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.all.*

import java.time.Instant

/** Validación acumulativa.
  *
  * ESTE ES EL PUNTO QUE DECIDE EL 422. El PDF muestra una respuesta con dos errores
  * simultáneos (UNKNOWN_SKU y COUPON_EXPIRED). `Either`/`EitherT` no pueden producirla:
  * su `flatMap` corta en el primer `Left` por diseño, porque el paso siguiente puede
  * depender del anterior. Eso está bien para el flujo general, y mal para validar.
  *
  * `Validated` no tiene `Monad` — a propósito. Sólo tiene `Applicative`, y por eso
  * `mapN` y `traverse` pueden ejecutar todas las ramas y juntar los errores: no hay
  * dependencia entre ellas que obligue a esperar. La regla mental:
  *
  *     mapN / traverse sobre Validated  -> acumulan
  *     flatMap sobre Either / EitherT   -> cortan
  *
  * Se usan las dos, cada una donde corresponde: `Validated` aquí dentro, `EitherT`
  * en el flujo del servicio (si la validación falla, no tiene sentido seguir a
  * calcular precio ni a escribir en Dynamo).
  */
object Validation:

  type Result[A] = ValidatedNel[ValidationError, A]

  def validate(
      request: PriceRequest,
      customer: Customer,
      catalog: Catalog,
      coupon: Option[Coupon],
      now: Instant
  ): Result[ValidOrder] =
    // Las líneas y las reglas del cupón que no dependen del importe se validan en
    // paralelo con `tupled`, que acumula ambos lados. Ésa es la única forma de
    // reproducir el 422 del PDF, que muestra UNKNOWN_SKU y COUPON_EXPIRED a la vez.
    (validateLines(request.items, catalog), validateCouponRules(coupon, customer.tier, now)).tupled
      .andThen { (lines, validCoupon) =>
        // `andThen` es el encadenado de Validated (corta, como `flatMap`) y aquí está
        // acotado a la ÚNICA regla que depende del subtotal: el mínimo del cupón no se
        // puede comprobar si las líneas no son válidas, porque no hay subtotal fiable.
        // Acotarlo así es la diferencia entre una dependencia real y fail-fast por inercia.
        validateMinimumAmount(validCoupon, subtotalOf(lines))
          .map(_ => ValidOrder(customer, lines, validCoupon))
      }

  def subtotalOf(lines: NonEmptyList[OrderLine]): Money =
    lines.foldLeft(Money.zero)((accumulated, line) => accumulated.plus(line.lineTotal))

  private def validateLines(
      items: List[RequestedItem],
      catalog: Catalog
  ): Result[NonEmptyList[OrderLine]] =
    NonEmptyList.fromList(items) match
      case None => ValidationError.EmptyOrder.invalidNel
      case Some(nonEmpty) =>
        // `traverse` recorre TODAS las líneas y acumula los errores de todas.
        // Un `foldLeft` con `Either` habría parado en la primera mala.
        nonEmpty.zipWithIndex.traverse(validateLine(_, _, catalog))

  private def validateLine(item: RequestedItem, index: Int, catalog: Catalog): Result[OrderLine] =
    val sku: Result[Sku] =
      Sku.from(item.sku)
        .leftMap(reason => ValidationError.InvalidSku(reason, index))
        .toValidatedNel
        .andThen { parsed =>
          if catalog.contains(parsed) then parsed.validNel
          else ValidationError.UnknownSku(item.sku, index).invalidNel
        }

    val quantity: Result[Quantity] =
      Quantity.from(item.quantity)
        .leftMap(reason => ValidationError.InvalidQuantity(reason, index))
        .toValidatedNel

    // mapN evalúa ambos lados aunque el primero ya haya fallado: un item con sku
    // desconocido Y cantidad cero produce dos entradas en el 422, no una.
    (sku, quantity).mapN { (validSku, validQuantity) =>
      OrderLine(validSku, validQuantity, catalog.priceOf(validSku).getOrElse(Money.zero))
    }

  /** Reglas del cupón que sólo miran al propio cupón y al tier: caducidad, uso agotado
    * y apilabilidad. Ninguna necesita el importe del pedido, así que pueden evaluarse
    * aunque las líneas sean inválidas — y por eso llegan al 422 junto a los errores de item. */
  private def validateCouponRules(
      coupon: Option[Coupon],
      tier: Tier,
      now: Instant
  ): Result[Option[Coupon]] =
    coupon match
      case None => None.validNel
      case Some(candidate) =>
        val code = candidate.code.value

        val notExpired: Result[Unit] =
          Validated.condNel(!candidate.isExpiredAt(now), (), ValidationError.CouponExpired(code, candidate.expiresAt))

        val notExhausted: Result[Unit] =
          Validated.condNel(!candidate.isExhausted, (), ValidationError.CouponExhausted(code))

        val stackable: Result[Unit] =
          Validated.condNel(candidate.stacksWith(tier), (), ValidationError.CouponNotStackableWithTier(code, tier))

        // Un cupón caducado, agotado y no apilable devuelve los tres errores de una vez.
        (notExpired, notExhausted, stackable).mapN((_, _, _) => Some(candidate))

  /** La única regla que necesita el subtotal, aislada para que su dependencia no
    * arrastre al resto de la validación al comportamiento fail-fast. */
  private def validateMinimumAmount(coupon: Option[Coupon], subtotal: Money): Result[Unit] =
    coupon match
      case None => ().validNel
      case Some(candidate) =>
        Validated.condNel(
          subtotal.isAtLeast(candidate.minOrderAmount),
          (),
          ValidationError.OrderBelowCouponMinimum(candidate.code.value, candidate.minOrderAmount)
        )
