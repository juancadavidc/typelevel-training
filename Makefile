# The local loop the brief asks for: `make up && make deploy && make test-integration`
# clean from a fresh checkout.
#
# node lives under nvm here, whose shell function is not available to make. Resolving the
# binary by path keeps the targets working in any shell, including CI.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ENDPOINT   ?= http://localhost:4566
API_PORT   ?= 8099
SCALA_VER  := 3.8.4

NODE_BIN := $(shell ls -d $$HOME/.nvm/versions/node/*/bin 2>/dev/null | tail -1)
export PATH := $(NODE_BIN):$(PATH)

# LocalStack accepts any credentials, but the AWS SDK refuses to start without them.
export AWS_ACCESS_KEY_ID     ?= test
export AWS_SECRET_ACCESS_KEY ?= test
export AWS_REGION            ?= us-east-1

.PHONY: help up down deploy seed test test-integration smoke run-api logs clean lambda-artifact

help: ## Show the available targets
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

up: .env ## Start LocalStack and the partner stub, and wait until they are ready
	docker compose up -d
	@echo "waiting for LocalStack…"
	@until curl -sf $(ENDPOINT)/_localstack/health >/dev/null 2>&1; do sleep 2; done
	@echo "LocalStack ready at $(ENDPOINT)"

down: ## Stop everything and remove the volumes
	docker compose down -v

# The token is required since LocalStack retired its Community edition in March 2026.
# Failing here with the reason beats a container that exits with a licensing error.
.env:
	@echo "ERROR: .env is missing."
	@echo "Copy .env.example to .env and set LOCALSTACK_AUTH_TOKEN"
	@echo "(free Hobby tier: https://app.localstack.cloud)"
	@exit 1

# `npm ci`, not `npm install`: it installs exactly what the lockfile pins and refuses to
# rewrite it. `npm install` rewrote `package-lock.json` on the first real deploy — harmless
# metadata churn, but it means the deploy is not reproducible and leaves the tree dirty,
# neither of which belongs in a loop the DoD asks to be clean from a fresh checkout.
cdk/node_modules: cdk/package.json cdk/package-lock.json
	cd cdk && npm ci --no-fund --no-audit
	@touch cdk/node_modules

# Not a file target: sbt already decides what needs rebuilding, and encoding the Scala
# source tree as make prerequisites would duplicate that badly. Re-running it when nothing
# changed costs the assembly re-zip, which is the right price for `deploy` never shipping a
# stale jar.
lambda-artifact: ## Assemble the Lambda and stage it for LocalStack's hot-reload bucket
	sbt lambda/hotReloadStage

# `lambda-artifact` is a prerequisite, not a documented manual step. The hot-reload bucket
# mounts the staged directory from disk, so a `deploy` that skipped the build would wire a
# function to whatever the last build left there — or to nothing at all on a fresh
# checkout, where the deploy still succeeds and only the first invocation fails. The DoD
# asks for this loop to be clean from a fresh checkout, which means the build belongs here.
deploy: cdk/node_modules lambda-artifact ## Deploy the CDK stacks to LocalStack
	cd cdk && ./node_modules/.bin/cdklocal bootstrap
	cd cdk && ./node_modules/.bin/cdklocal deploy --all --require-approval never
	@$(MAKE) --no-print-directory seed

seed: ## Load the brief's example customers and coupons
	@chmod +x local/seed.sh
	@AWS_ENDPOINT_URL=$(ENDPOINT) ./local/seed.sh

test: ## Unit tests — no Docker, no LocalStack
	sbt test

test-integration: ## Integration tests against LocalStack via testcontainers
	sbt "service/testOnly *IntegrationSuite"

run-api: ## Run the API against the local stack
	PORT=$(API_PORT) \
	AWS_ENDPOINT_URL=$(ENDPOINT) \
	LOYALTY_BASE_URI=http://localhost:8081 \
	sbt "service/runMain com.kata.pricing.service.Main"

smoke: ## Hit the running API with the brief's example request
	@curl -s -X POST http://localhost:$(API_PORT)/orders/price \
		-H 'Content-Type: application/json' \
		-d '{"customerId":"cust-123","items":[{"sku":"SKU-001","quantity":2},{"sku":"SKU-045","quantity":1}],"couponCode":"SUMMER10"}' \
		| python3 -m json.tool

logs: ## Tail LocalStack's logs
	docker compose logs -f localstack

clean: ## Remove build output
	sbt clean
	rm -rf cdk/cdk.out cdk/node_modules
