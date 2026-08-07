package com.kata.pricing.domain

/** Catálogo de precios como valor puro.
  *
  * Decisión deliberada: el PDF describe la búsqueda en DynamoDB sólo para customer y
  * coupon ("look up customer tier and coupon rule from DynamoDB"), y el modelo de datos
  * no incluye una tabla de productos. Así que el catálogo entra como dato al núcleo
  * puro en vez de ser una cuarta tabla. Ventaja concreta: la validación de "SKU
  * desconocido" se puede probar sin runtime de efectos ni LocalStack.
  *
  * Si en la revisión piden persistirlo, el cambio es local: `Catalog` pasa a ser un
  * álgebra `F[_]` y la validación recibe el `Map` ya resuelto. La firma pura no cambia.
  */
final class Catalog private (private val pricesBySku: Map[String, Money]):
  def priceOf(sku: Sku): Option[Money] = pricesBySku.get(sku.value)
  def contains(sku: Sku): Boolean      = pricesBySku.contains(sku.value)
  def size: Int                        = pricesBySku.size

object Catalog:
  val empty: Catalog = new Catalog(Map.empty)

  def of(entries: (String, BigDecimal)*): Catalog =
    new Catalog(entries.map((sku, price) => sku -> Money(price)).toMap)

  def fromMap(entries: Map[String, Money]): Catalog = new Catalog(entries)
