# Everything needed to run, stop, debug, lint and test Ledgerly. `make` lists the targets.
# Needs JDK 25 from mise (see mise.toml): run in a shell with mise activated, or prefix: mise exec -- make test
.DEFAULT_GOAL := help
MVN := ./mvnw
IMAGE := ledgerly:0.0.1-SNAPSHOT

.PHONY: help
help: ## List the targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

# --- run and stop -------------------------------------------------------------------------------------------------

.PHONY: deps
deps: ## Start only the dependencies (Postgres, object store, ClamAV, Grafana)
	docker compose up -d postgres seaweedfs clamav lgtm

.PHONY: run
run: ## Run the application from Maven on the host (profile local; starts the dependencies from compose.yaml)
	SPRING_PROFILES_ACTIVE=local $(MVN) spring-boot:run

.PHONY: debug
debug: ## Like run, but waits for a debugger on port 5005
	SPRING_PROFILES_ACTIVE=local $(MVN) spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"

.PHONY: image
image: ## Build the container image
	$(MVN) -B -DskipTests spring-boot:build-image

.PHONY: up
up: image ## Build the image and run everything in Docker (API http://localhost:8080, Swagger UI /swagger-ui/index.html)
	docker compose --profile app up -d postgres seaweedfs clamav lgtm app

.PHONY: down
down: ## Stop everything and keep the data
	docker compose --profile app down

.PHONY: reset
reset: ## Stop everything and delete the data
	docker compose --profile app down -v

.PHONY: logs
logs: ## Follow the application's container logs
	docker compose --profile app logs -f app

.PHONY: ps
ps: ## Show the running containers
	docker compose --profile app ps

# --- API checks ---------------------------------------------------------------------------------------------------

.PHONY: api-test
api-test: ## Run the Bruno collection in bruno/ against the running app (make up first; needs Node)
	cd bruno && npx --yes @usebruno/cli@latest run --env local -r

# --- quality ------------------------------------------------------------------------------------------------------

.PHONY: format
format: ## Format the Java code (Spotless)
	$(MVN) -q spotless:apply

.PHONY: lint
lint: ## Check formatting, lint the OpenAPI file
	$(MVN) -q spotless:check
	npx --yes @redocly/cli@latest lint api/openapi.yaml

.PHONY: test
test: ## Run all tests (needs Docker for Testcontainers)
	$(MVN) -B test

.PHONY: verify
verify: ## Everything CI runs on the build: tests, Spotless, NullAway
	$(MVN) -B verify

.PHONY: test-one
test-one: ## Run one test class: make test-one T=ExpensesApiTest
	$(MVN) -B test -Dtest=$(T)

.PHONY: clean
clean: ## Delete build output
	$(MVN) -q clean
