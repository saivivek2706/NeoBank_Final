.DEFAULT_GOAL := help
.PHONY: help env up down logs build test verify e2e lint frontend load-test clean

help: ## List the available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

env: ## Generate .env with fresh random secrets (no-op if it exists)
	./scripts/bootstrap-env.sh

up: env ## Start the full stack (builds images)
	docker compose up --build -d

down: ## Stop the stack (keeps data volumes)
	docker compose down

clean: ## Stop the stack and DELETE all data volumes
	docker compose down -v

logs: ## Tail all service logs
	docker compose logs -f --tail=100

build: ## Build all backend modules, skipping tests
	mvn -DskipTests package

test: ## Unit + integration tests (needs Docker for Testcontainers)
	mvn test

verify: ## Checkstyle + tests + JaCoCo coverage (what CI runs)
	mvn verify

lint: ## Checkstyle (backend) and ESLint (frontend)
	mvn validate
	cd frontend && npm run lint

frontend: ## Install, lint and build the frontend
	cd frontend && npm ci && npm run lint && npm run build

e2e: env ## Full-stack saga test via Testcontainers (slow)
	mvn -Pe2e verify -pl e2e-tests

load-test: ## k6 load test against a running stack
	k6 run -e VUS=5 -e DURATION=1m load-tests/transfer-load.js
