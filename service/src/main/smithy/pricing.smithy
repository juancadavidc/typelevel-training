$version: "2"

namespace com.kata.pricing

use alloy#simpleRestJson

@simpleRestJson
service PricingService {
    version: "1.0.0"
    operations: [PriceOrder]
}

@http(method: "POST", uri: "/orders/price", code: 200)
operation PriceOrder {
    input: PriceOrderRequest
    output: PricedOrderResponse
    errors: [ValidationException]
}

structure PriceOrderRequest {
    @required
    customerId: CustomerId

    @required
    items: OrderItemList

    couponCode: CouponCode
}

structure PricedOrderResponse {
    @required
    orderId: OrderId

    @required
    customerId: CustomerId

    @required
    status: OrderStatus

    @required
    items: PricedItemList

    @required
    subtotal: Money

    @required
    discountAmount: Money

    @required
    total: Money

    couponApplied: CouponCode

    /// ISO-8601, as the brief's example response and its data model both specify.
    ///
    /// The trait is not decoration: without it smithy4s falls back to the protocol's
    /// default JSON timestamp format, which is epoch-seconds, and the field goes out as
    /// `1787072056.690919` instead of `"2026-07-22T14:32:00Z"`. Nothing in the model said
    /// otherwise, so nothing was wrong with the codec — the contract was underspecified.
    @timestampFormat("date-time")
    @required
    createdAt: Timestamp
}

@error("client")
@httpError(422)
structure ValidationException {
    @required
    errors: ValidationErrorList
}

structure ValidationErrorDetail {
    @required
    code: String

    @required
    field: String

    @required
    message: String
}

list ValidationErrorList {
    member: ValidationErrorDetail
}

list OrderItemList {
    member: OrderItemInput
}

list PricedItemList {
    member: PricedItem
}

structure OrderItemInput {
    @required
    sku: String

    @required
    quantity: Integer
}

structure PricedItem {
    @required
    sku: String

    @required
    quantity: Integer

    @required
    unitPrice: Money

    @required
    lineTotal: Money
}

enum OrderStatus {
    PRICED = "PRICED"
}

@length(min: 1)
string CustomerId

@length(min: 1)
string CouponCode

@length(min: 1)
string OrderId

bigDecimal Money
