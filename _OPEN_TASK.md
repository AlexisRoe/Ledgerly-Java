IMPORTANT: To be done, after system is fully implemented

## Task 1

Make the hole application running in docker

**Status: DONE, verified.** `compose.yaml` has an `app` service (compose profile `app`, image `ledgerly:0.0.1-SNAPSHOT`, `local` profile, Postgres health check). Started with `docker compose --profile app up`: readiness 200, Swagger UI 200. ClamAV is pinned to `linux/amd64` because it has no arm64 image.

## Task 2

Create a MAKE file with all commands needed to run, stop, debug, lint, test the application

**Status: DONE, verified.** `Makefile` (`make` lists targets): deps, run, debug (port 5005), image, up, down, reset, logs, ps, format, lint, test, verify, test-one, clean. Checked: `make help`, `make -n up`, and `make lint` (passes). The Java targets need JDK 25 from mise on the PATH.

## Task 3

Add JSDOC like comments on methods, classes and variables to describe their intentional purpose or shape, ingoing and outgoing data. Do NOT describe JAVA internal methods.

**Status: DONE, verified (Spotless, compile, full `verify` green in the isolated copy).** About 350 doc comments added across all seven modules (154 files): types, records and their fields, use cases (inputs, outputs, errors thrown), repositories, events, controllers, filters, config. Left undocumented on purpose: constructors that only store injected dependencies, one-line event handlers covered by their class comment, and `@Override` methods (documented where declared). No behaviour changed.

## Task 4

Create Bruno files for api testing, including local setup, test cases

**Status: DONE, verified.** `bruno/` (collection, `local` environment, 47 requests in 6 folders with assertions, README) plus `make api-test`. Run with the Bruno CLI against the Docker app with demo data: 47/47 requests, 74/74 assertions, twice in a row (repeatable). Checks that an unauthenticated or wrongly-roled caller is refused, not only that the happy path works.

## Task 5

If not already happening, seed the database with a base set of data, so that testing can happen immeadabtly

**Status: DONE, verified.** `DevDataSeeder` (profile `local & !test`) creates 4 users, the team "Acme Travel" with OWNER/APPROVER/MEMBER, a pending invitation and 9 expenses in every status. Idempotent: restart adds nothing. Verified by `DevDataSeederTest` (3 tests) and by logging in as each user against the running container. Documented in `docs/deployment.md` ("Demo data"). It was not there before: only test seeders existed.

## Task 6

Implementing lintern, prettier, test pipelines and implement automatic running checks on push to main via PR to Github

**Status: DONE, partly verified.** Already there from M11: Spotless (Java), Error Prone + NullAway, Redocly OpenAPI lint, gitleaks, tests, image build, Trivy, SBOM. Added now: Prettier for YAML/JSON (`.prettierrc.json`, a `format-config` CI job, a pre-commit hook, `make lint` / `make format`), CI runs for pull requests *into main* and for pushes to main, a run is cancelled when the PR gets a newer push, and failed runs keep the surefire reports. Verified locally: `make lint` passes; the workflow files parse. **Not verified:** the GitHub Actions run itself (it cannot run here; the first PR will show it). **Not done, needs you:** requiring these checks and a PR before merging is a branch-protection setting in GitHub (Settings > Branches), which I did not change; the list of check names is in the comment at the top of `ci.yml`. Markdown is deliberately not reformatted (it would rewrite your prose and tables). The Bruno API run is not in CI: it needs the whole stack including ClamAV signatures, which is minutes of start-up and could not be tested here.

## Task 7 

About testing, see if you can make testing better. Tests should never test java or library methods, just custom code. It should test expected data flows, edge-cases and what methods should not do or allow

**Status: DONE, verified.** Reviewed all ~60 test classes and measured coverage with JaCoCo (70% of instructions, 46% of branches overall, run once from the command line; JaCoCo is not added to the build).
- Removed: `ConcurrencyStudyTest` (18 tests that only exercised JDK executors, locks and `ScopedValue`, none of our code). It stays in git history (`c6ba439`) and the learning log says so.
- Found otherwise sound: the existing tests are about our code (domain rules, role matrix, OWASP API Top 10 cases, Keycloak, S3/ClamAV adapters).
- Added 28 tests in 5 classes for the decisions that had no test: partial edits (`UpdateExpenseTest`: unchanged fields, only names announced never values, blank note, stale version, wrong role, outsider, future date), the streamed CSV export (`LazyCsvInputStreamTest`: laziness, page order, formula and quote safety, read sizes), what an import tells the user about skipped lines (`ImportSkipListenerTest`: no internal message leaks, restart is idempotent, long messages), chunked bodies over the limit (`ChunkedBodyLimitTest`), and the event redelivery age rule (`EventMaintenanceTest`).
- Checked that they bite: breaking the redelivery age, the formula guard and the message hiding on purpose made exactly the matching test fail.
- Not done: a coverage gate in the build, and tests for `ProblemExceptionHandler` branches that need a deliberately failing endpoint.

## Task 8

If not already existing, does a /docs endpoint exists for the OpenApiSpecs?

**Status: DONE, verified.** It did not exist; only `/swagger-ui/index.html` (local profile only). Added `GET /docs`, local profile only, redirecting to the Swagger UI that renders `api/openapi.yaml` (open without a token; POST etc. stay refused). Tests in `SwaggerUiProfileTest`: local `/docs` ends on the UI page, other profiles answer 401 for `/docs`. Deliberately not served outside local (OWASP API9/API8); documented in `docs/deployment.md`.

## Task 9

Its about hardening the local setup of supply-chain-attacks, secrets and such