package com.kata.pricing.service.adapter.dynamo

import cats.effect.Async
import cats.syntax.all.*
import com.kata.pricing.domain.{Customer, CustomerId, Tier}
import com.kata.pricing.domain.port.CustomerRepo
import natchez.Trace
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest}

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** A `CustomerRepo` backed by DynamoDB, instrumented with natchez.
  *
  * Note what is *not* here: the `CustomerRepo` trait in `domain` is unchanged, and
  * `PricingFlow` still asks for nothing but `Monad`. Spans live in the implementation
  * because that is where time is actually spent — a span around a `BigDecimal`
  * calculation measures noise. Instrumenting at this layer keeps `domain` free of
  * `natchez` and satisfies the DoD's propagation requirement at the same time, since
  * these spans become children of the request span opened in the middleware without
  * either side referring to the other.
  *
  * `Trace[F]` is requested as a constraint, not a constructor parameter: it is a
  * capability of the effect, like `Async`. The caller decides what provides it.
  */
final class CustomerRepoDynamo[F[_]: Async: Trace](
    client: DynamoDbAsyncClient,
    tableName: String
) extends CustomerRepo[F]:

  def find(id: CustomerId): F[Option[Customer]] =
    Trace[F].span("dynamodb.get-customer") {
      Trace[F].put(
        "db.system"        -> "dynamodb",
        "db.operation"     -> "GetItem",
        "db.table"         -> tableName,
        "pricing.customer" -> id.value
      ) *> fetch(id)
    }

  private def fetch(id: CustomerId): F[Option[Customer]] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("customerId" -> AttributeValue.fromS(id.value)).asJava)
      .build()

    // `fromCompletableFuture` bridges the SDK's async client without blocking a thread,
    // and cancellation propagates correctly — the reason to prefer the async client over
    // wrapping the blocking one in `Sync.blocking`.
    Async[F]
      .fromCompletableFuture(Async[F].delay(client.getItem(request)))
      .flatMap { response =>
        if !response.hasItem then Trace[F].put("db.hit" -> false).as(None)
        else
          val item = response.item().asScala.toMap
          Trace[F].put("db.hit" -> true).as(decode(id, item))
      }

  /** Returns `None` on a malformed row rather than throwing: an unreadable item is the
    * same business outcome as a missing one — the customer cannot be priced — and
    * `CustomerRepo` returns `F[Option[_]]` precisely so the flow does not need
    * `MonadError` to express that. */
  private def decode(id: CustomerId, item: Map[String, AttributeValue]): Option[Customer] =
    for
      tier      <- item.get("tier").map(_.s()).flatMap(Tier.from(_).toOption)
      createdAt <- item.get("createdAt").map(_.s()).flatMap(raw =>
                     Either.catchNonFatal(Instant.parse(raw)).toOption
                   )
    yield Customer(
      id = id,
      tier = tier,
      name = item.get("name").map(_.s()),
      createdAt = createdAt
    )
