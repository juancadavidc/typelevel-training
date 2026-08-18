#!/usr/bin/env bash
#
# Seeds the local tables with exactly the data the brief's worked example needs, so that
# `make smoke` can assert its numbers rather than whatever happens to be in the database.
#
# Uses plain `aws --endpoint-url` rather than `awslocal` to keep the dependency list
# short: the AWS CLI is already required, awslocal would be one more thing to install.

set -euo pipefail

ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"
REGION="${AWS_REGION:-us-east-1}"

# LocalStack accepts any credentials but the SDK refuses to run without some.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_REGION="$REGION"

ddb() { aws dynamodb "$@" --endpoint-url "$ENDPOINT" --region "$REGION" >/dev/null; }

echo "seeding Customers…"
# cust-123 is the brief's example customer. GOLD because SUMMER10 only stacks with SILVER
# and GOLD, so a BASIC tier here would reject the coupon the example applies.
#
# It deliberately has *no* partner perk in `local/wiremock/mappings/loyalty-perk.json`.
# It used to, and the example then returned discountAmount 13.48 instead of the 8.99 the
# brief pins, because coupon and perk stack. The brief's step 3 is an "(Optional partner
# check)" that never states a numeric effect, and its worked example is exactly 10% floored
# — so the example assumes no perk, and matching it is a fixture decision. Making the perk
# not stack would be inventing a business rule the brief explicitly asks us not to add.
ddb put-item --table-name Customers --item '{
  "customerId": {"S": "cust-123"},
  "tier":       {"S": "GOLD"},
  "name":       {"S": "Ada Lovelace"},
  "createdAt":  {"S": "2026-01-15T09:00:00Z"}
}'

ddb put-item --table-name Customers --item '{
  "customerId": {"S": "cust-456"},
  "tier":       {"S": "BASIC"},
  "createdAt":  {"S": "2026-03-02T11:30:00Z"}
}'

# Where the partner perk lives now, so brief step 3 stays demonstrable by hand: same tier
# and same coupon as cust-123, plus the 5% perk the stub serves for this id only. The two
# customers together show the stacking rule — 8.99 for cust-123, 13.48 for cust-789.
ddb put-item --table-name Customers --item '{
  "customerId": {"S": "cust-789"},
  "tier":       {"S": "GOLD"},
  "name":       {"S": "Grace Hopper"},
  "createdAt":  {"S": "2026-02-20T08:45:00Z"}
}'

echo "seeding Coupons…"
# SUMMER10 is the brief's example coupon: 10% off, and the response it pins
# (discountAmount 8.99 on a subtotal of 89.97) is what proves the rounding-down rule.
ddb put-item --table-name Coupons --item '{
  "couponCode":         {"S": "SUMMER10"},
  "discountPercent":    {"N": "10"},
  "minOrderAmount":     {"N": "20.00"},
  "usageLimit":         {"N": "100"},
  "usageCount":         {"N": "0"},
  "expiresAt":          {"S": "2027-06-30T23:59:59Z"},
  "stackableWithTiers": {"L": [{"S": "SILVER"}, {"S": "GOLD"}]}
}'

# An expired coupon, so the 422 path can be exercised by hand without editing data.
ddb put-item --table-name Coupons --item '{
  "couponCode":         {"S": "EXPIRED5"},
  "discountPercent":    {"N": "5"},
  "minOrderAmount":     {"N": "10.00"},
  "usageLimit":         {"N": "50"},
  "usageCount":         {"N": "0"},
  "expiresAt":          {"S": "2026-06-30T00:00:00Z"},
  "stackableWithTiers": {"L": [{"S": "GOLD"}]}
}'

echo "seed complete."
