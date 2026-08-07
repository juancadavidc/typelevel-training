package com.kata.pricing.domain

import cats.data.Validated
import weaver.*

import com.kata.pricing.domain.Fixtures.*

object ValidationSuite extends SimpleIOSuite:

  private def request(items: (String, Int)*)(coupon: Option[String] = None): PriceRequest =
    PriceRequest(
      CustomerId.unsafe("cust-123"),
      items.toList.map(RequestedItem.apply),
      coupon.map(CouponCode.unsafe)
    )

  private def errorsOf(result: Validation.Result[?]): List[ValidationError] =
    result match
      case Validated.Invalid(errors) => errors.toList
      case Validated.Valid(_)        => Nil

  pureTest("una petición válida produce un ValidOrder") {
    val result = Validation.validate(
      request(("SKU-001", 2), ("SKU-045", 1))(),
      customer(),
      catalog,
      coupon = None,
      now
    )
    expect(result.isValid)
  }

  /** ESTE es el test que delata haber usado EitherT donde iba Validated.
    *
    * El PDF muestra un 422 con UNKNOWN_SKU y COUPON_EXPIRED simultáneos. Con `EitherT`
    * la lista tendría exactamente un elemento y este test fallaría — no por un error de
    * cálculo, sino por haber elegido la abstracción equivocada.
    */
  pureTest("acumula errores de items y de cupón en la misma respuesta") {
    val expired = coupon(code = "SUMMER10", expiresAt = now.minusSeconds(86_400))

    val result = Validation.validate(
      request(("SKU-001", 2), ("SKU-999", 1))(Some("SUMMER10")),
      customer(),
      catalog,
      Some(expired),
      now
    )

    val codes = errorsOf(result).map(_.code)
    expect.all(
      result.isInvalid,
      codes.contains("UNKNOWN_SKU"),
      codes.contains("COUPON_EXPIRED"),
      codes.size == 2
    )
  }

  pureTest("un mismo item con sku desconocido y cantidad inválida da dos errores, no uno") {
    val result = Validation.validate(request(("SKU-999", 0))(), customer(), catalog, None, now)

    val codes = errorsOf(result).map(_.code)
    expect.all(
      codes.contains("UNKNOWN_SKU"),
      codes.contains("INVALID_QUANTITY"),
      codes.size == 2
    )
  }

  pureTest("acumula los errores de todos los items, no sólo del primero malo") {
    val result =
      Validation.validate(request(("SKU-998", 1), ("SKU-001", 1), ("SKU-999", 1))(), customer(), catalog, None, now)

    val fields = errorsOf(result).map(_.field)
    expect.all(fields.contains("items[0].sku"), fields.contains("items[2].sku"), fields.size == 2)
  }

  pureTest("un cupón caducado, agotado y no apilable devuelve los tres errores a la vez") {
    val bad = coupon(
      expiresAt = now.minusSeconds(1),
      usageLimit = 5,
      usageCount = 5,
      tiers = Set(Tier.Gold)
    )

    val result =
      Validation.validate(request(("SKU-001", 1))(Some("SUMMER10")), customer(Tier.Basic), catalog, Some(bad), now)

    val codes = errorsOf(result).map(_.code)
    expect.all(
      codes.contains("COUPON_EXPIRED"),
      codes.contains("COUPON_USAGE_EXCEEDED"),
      codes.contains("COUPON_NOT_STACKABLE"),
      codes.size == 3
    )
  }

  pureTest("un pedido por debajo del mínimo del cupón se rechaza") {
    val demanding = coupon(minOrderAmount = BigDecimal("100.00"))

    val result =
      Validation.validate(request(("SKU-100", 1))(Some("SUMMER10")), customer(), catalog, Some(demanding), now)

    expect(errorsOf(result).map(_.code) == List("ORDER_BELOW_MINIMUM"))
  }

  pureTest("un pedido sin items falla con EMPTY_ORDER") {
    val result = Validation.validate(request()(), customer(), catalog, None, now)
    expect(errorsOf(result).map(_.code) == List("EMPTY_ORDER"))
  }

  pureTest("el mínimo del cupón no se evalúa si las líneas son inválidas") {
    // Delimita el único `andThen` de la validación: sin líneas válidas no hay subtotal,
    // así que ORDER_BELOW_MINIMUM no puede afirmarse y se omite. Las demás reglas del
    // cupón sí se evalúan (ver el test de acumulación de arriba).
    val demanding = coupon(minOrderAmount = BigDecimal("100.00"))

    val result =
      Validation.validate(request(("SKU-999", 1))(Some("SUMMER10")), customer(), catalog, Some(demanding), now)

    val codes = errorsOf(result).map(_.code)
    expect.all(codes.contains("UNKNOWN_SKU"), !codes.contains("ORDER_BELOW_MINIMUM"))
  }
