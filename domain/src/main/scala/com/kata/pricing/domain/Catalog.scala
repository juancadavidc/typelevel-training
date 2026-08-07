package com.kata.pricing.domain

/** The price catalog as a pure value.
  *
  * A deliberate decision: the brief describes the DynamoDB lookup for customer and
  * coupon only ("look up customer tier and coupon rule from DynamoDB"), and the data
  * model defines no products table. So the catalog enters the pure core as data instead
  * of being a fourth table. Concrete benefit: "unknown SKU" validation can be tested
  * with no effect runtime and no LocalStack.
  *
  * If a reviewer asks for it to be persisted, the change is local: `Catalog` becomes an
  * `F[_]` algebra and validation receives the resolved `Map`. The pure signature does
  * not change.
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
