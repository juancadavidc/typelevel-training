package com.kata.pricing.service.adapter.dynamo

import cats.effect.Async
import cats.syntax.all.*
import com.kata.pricing.domain.PricedOrder
import com.kata.pricing.domain.port.OrderRepo
import natchez.Trace
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, PutItemRequest}

import scala.jdk.CollectionConverters.*

/** The `OrderRepo` port, driven by DynamoDB. */
final class OrderRepoDynamo[F[_]: Async: Trace](
    client: DynamoDbAsyncClient,
    tableName: String
) extends OrderRepo[F]:

  /** The write that DynamoDB Streams turns into an event.
    *
    * There is no outbox row and no `TransactWriteItems` here, and that is deliberate:
    * with NEW_IMAGE streams enabled on this table, the write *is* the event. That is what
    * change-data-capture buys, and it is the resolution of the contradiction between the
    * brief's data model and its Definition of Done.
    */
  def save(order: PricedOrder): F[Unit] =
    Trace[F].span("dynamodb.put-order") {
      Trace[F].put("db.table" -> tableName, "pricing.order" -> order.orderId.value) *>
        put(order)
    }

  private def put(order: PricedOrder): F[Unit] =
    val items = order.lines.toList.map { line =>
      AttributeValue.fromM(
        Map(
          "sku"       -> Attr.s(line.sku.value),
          "quantity"  -> Attr.n(line.quantity.value.toString),
          "unitPrice" -> Attr.n(line.unitPrice.amount.toString),
          "lineTotal" -> Attr.n(line.lineTotal.amount.toString)
        ).asJava
      )
    }

    val attributes = Map(
      "orderId"        -> Attr.s(order.orderId.value),
      "customerId"     -> Attr.s(order.customerId.value),
      "status"         -> Attr.s(order.status.code),
      "items"          -> AttributeValue.fromL(items.asJava),
      "subtotal"       -> Attr.n(order.subtotal.amount.toString),
      "discountAmount" -> Attr.n(order.discountAmount.amount.toString),
      "total"          -> Attr.n(order.total.amount.toString),
      "createdAt"      -> Attr.s(order.createdAt.toString),
      "updatedAt"      -> Attr.s(order.createdAt.toString)
    ) ++ order.couponApplied.map(code => "couponCode" -> Attr.s(code.value))

    val request = PutItemRequest.builder().tableName(tableName).item(attributes.asJava).build()

    Async[F].fromCompletableFuture(Async[F].delay(client.putItem(request))).void
