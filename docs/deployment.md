# Deploying Ledgerly

## Build the image

```sh
./mvnw -DskipTests spring-boot:build-image      # -> ledgerly:<version>
```

Cloud Native Buildpacks (Paketo) build it: no Dockerfile to maintain. The image runs on a JRE 25, as a
**non-root user** (uid 1002), and carries a class-data-sharing / AOT cache made by a training run at build time. CI
builds it on every push, checks the user, scans it with Trivy (high and critical findings with a fix fail the
build) and keeps the CycloneDX SBOM as a build artifact (`target/classes/META-INF/sbom/application.cdx.json`).

## Run it

Set `SPRING_PROFILES_ACTIVE=prod`. The prod profile has **no defaults**: it refuses to start, naming every missing
setting and its environment variable, unless all of these are set.

| Setting (environment variable) | What |
| --- | --- |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | PostgreSQL (Flyway migrates it at start-up) |
| `LEDGERLY_AUTH_JWT_PRIVATE_KEY`, `LEDGERLY_AUTH_JWT_PUBLIC_KEY` | ES256 key pair for access tokens (see `.env.example` for the `openssl` commands). Not needed with the `keycloak` profile |
| `LEDGERLY_STORAGE_S3_ENDPOINT`, `_BUCKET`, `_ACCESS_KEY`, `_SECRET_KEY` | S3-compatible object store (private bucket) |
| `LEDGERLY_RECEIPTS_CLAMAV_HOST`, `_PORT` | clamd |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | only with the `keycloak` profile |

Optional: `LEDGERLY_SECURITY_ALLOWED_ORIGINS` (browser origins allowed by CORS), `LEDGERLY_LIMITS_*` (request limits),
`SERVER_FORWARD_HEADERS_STRATEGY=native` behind a reverse proxy (so rate limits see the client's address).

```sh
docker run --rm -p 8080:8080 -p 8081:8081 --env-file prod.env ledgerly:0.0.1-SNAPSHOT
```

## Ports and probes

| Port | What | Publish it? |
| --- | --- | --- |
| 8080 | The API | yes, behind TLS |
| 8081 | Actuator: `/actuator/health` (+ `/liveness`, `/readiness`), `/actuator/info`, `/actuator/prometheus` | no: only to your orchestrator and metrics scraper |

On SIGTERM the application stops taking requests and lets running ones finish (up to 30 seconds), so give the
container a `terminationGracePeriodSeconds` of at least that.

## Things to know

- The login lock and the request rate limits are kept in the memory of one instance. Two instances each keep their own
  (see the M10 notes in `learning-log.md`).
- Logs are ECS JSON on stdout with `traceId`, `userId` and `teamId`; they hold no emails, tokens or file contents.
