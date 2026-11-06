# Learning log

## M0 · Plain-Java core

- **Currency rule:** `Money` accepts any `java.util.Currency` whose `getDefaultFractionDigits()` is ≥ 0. Pseudo codes (XAU, XTS, XXX) report -1 and are rejected. `Currency` has no "active" flag, so withdrawn historical codes are still accepted.
- **Explain it back (yours to write):** what you built, why `Money` is a record and `Expense` is not, one thing still unclear.

## M1 · First Boot app

- **Tooling notes:** Boot 4.1.2 does not manage Spring Modulith, so its BOM is imported (2.1.2). `@NullMarked` applies per package, so every package needs a `package-info.java`; NullAway runs on main sources only. Docker Compose support is skipped in tests by default, so tests need no Docker yet.
- **Not verified here:** the OTLP traces and logs reaching Grafana (`docker compose up`, open http://localhost:3000). Run the app with `SPRING_PROFILES_ACTIVE=local` and check.
- **Explain it back (yours to write):** what you built, why Spring needed records / annotations / reflection for it, one thing still unclear.

## M2 · Persist expenses

- **Tooling notes:** Hibernate does not allow `AttributeConverter`s on `@Id`, so `ExpenseId` is an `@Embeddable` record used as `@EmbeddedId`; `TeamId` and `UserId` use auto-applied converters. `Money` is an embeddable record; its `char(3)` currency column needs `@JdbcTypeCode(SqlTypes.CHAR)` to pass schema validation. `ExpenseRepository` extends `Repository`, so no un-scoped `findById` exists.
- **Explain it back (yours to write):** what you built, entity identity vs value equality, one thing still unclear.

## M3 · Contract, validation, errors

- **Decisions:**
  - `GET` list and filters are left for M4; the list needs paging first.
  - Idempotency stores only the created resource id and status; a replay returns the expense as it is now, not a byte copy.
  - `InvalidInput` (400) and `NotDraft` (409) were split out of `RuleViolated` (422) so every domain refusal maps to the status the plan asks for.
  - `ReceiptSummary` moved to the public `com.ledgerly.expenses` package, next to `ReceiptChecks`.
  - The app needs `SPRING_PROFILES_ACTIVE=local` (or `test`): the debug-header `CurrentUser` only exists there, and `prod` fails to start until M5.
- **Gotchas:** Spring builds the `ProblemDetail` for framework errors inside `handleExceptionInternal`, so enrich the returned body, not the argument. Hibernate bumps `@Version` on flush, so the use case flushes before it returns the ETag. `ExpenseException` is sealed, which makes the mapping `switch` exhaustive.
- **Explain it back (yours to write):** what you built, why sealed types and pattern-matching `switch` helped, one thing still unclear.

## M4 · Paging and filtering

- **Decisions:**
  - `ExpenseRepository` declares `findAll(Specification, Pageable)` itself instead of extending `JpaSpecificationExecutor`, so the interface still has no inherited `findById`. `ListExpenses` always starts from `inTeam(teamId)`.
  - The response mirrors Spring Data's `PagedModel` shape (`content` + `page`) as a generated DTO, so the spec stays the single source of truth.
  - `sort` is parsed into a `SortField` enum; the id is added as a unique tie-breaker so pages never overlap.
  - `shared.paging.Cursor` (Base64url JSON of `(Instant, UUID)`) and `KeysetPaging` are tested against `expenses` and get wired to `/audit-events` in M9.
- **Gotchas:** the generated interface is `@Validated`, so parameter constraints throw `ConstraintViolationException` (not `HandlerMethodValidationException`) and need their own handler, or they become 500s. The spec's `maximum: 100` and `LedgerlyLimits.maxPageSize` are both checked.
- **Explain it back (yours to write):** what you built, why keyset paging beats offset paging on a growing table, one thing still unclear.

## M5 · Authentication

- **Decisions:**
  - The generated OpenAPI interfaces and models now live in `com.ledgerly.shared.api`, so expenses and identity both implement their own interface from one contract.
  - Access tokens are ES256 JWTs with only `sub`, `iat`, `exp` and `jti`. The decoder is built from the in-process public key with a Nimbus `JWTProcessor`, because Spring's `withPublicKey` builder is RSA-only.
  - Registration hashes the password even for taken emails and answers 202 either way; login runs a dummy hash for unknown emails.
  - The verification token travels in an internal `VerificationLinkIssued` event (never published to other modules, never logged except by the `local`-only logger). The public `UserRegistered` / `UserVerified` events carry no secrets.
  - `Login` and `Refresh` use `noRollbackFor = Unauthenticated`, otherwise the failure counter and the family revocation would be rolled back along with the 401.
  - Refresh and logout check `X-Requested-With: ledgerly` in code, because the generated code only requires the header, not its value. See ADR 0004.
  - The debug user header is gone. `X-Debug-Role` stays until M6.
- **Gotchas:** the actuator port needs its own security chain (health and info only), otherwise the secured app answers the probes with 401. Tests now run against a real server (`RANDOM_PORT`) so the security filters are exercised. A mutable test clock moves time forward for expiry and lockout tests.
- **Known limits:** the failure counter on `User` has no `@Version`, so two truly simultaneous bad logins can count as one. Fine for a lockout threshold; Bucket4j limits arrive in M10.
- **Explain it back (yours to write):** what you built, why `Instant`, `Duration` and an injected `Clock` made the expiry tests possible, one thing still unclear.

## M6 · Teams and authorization

- **Decisions:**
  - Roles are never in the token. `TeamAuthorization` (used from `@PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")`) reads the role from the database on every call: not a teammate is 404, a teammate with the wrong role is 403. It uses an `EnumSet` for the allowed roles and `Role.includes` for the ladder.
  - The use cases re-check the caller's role *inside* their transaction after taking the team-row lock (`SELECT … FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`). Checking only at the controller would let a caller who was demoted a moment earlier still act.
  - `LastOwnerConcurrencyTest` races two owners demoting, removing and leaving each other with a `CyclicBarrier`. I confirmed it is meaningful: with the lock replaced by a plain `findById`, all 30 runs fail.
  - Inviting never checks whether the address is registered or already a teammate, so the response cannot leak either. "Already a teammate" is only reported on accept, to the invited person.
  - Invitation expiry is computed from the `Clock` when read (`Invitation.status(now)`), no scheduler. Re-inviting revokes and flushes the old pending row first, because of the partial unique index.
  - `GET /me` lives in teams (D5). Pending invitations appear only for a verified email, and only unexpired ones.
  - `MembershipLookup` in expenses now calls `TeamsFacade`; the debug role header and `ProdProfileTest` are gone. `teamId` joins `userId` on every log line through `MdcTeamFilter`.
  - Teams events are a sealed `TeamEvent` (ids only, no emails) published with `ApplicationEventPublisher`; nobody listens until M9.
- **Edge case (documented and tested):** a removed claimant gets 404 on withdraw, reopen and read, because they are no longer a teammate. Approvers can still approve or reject the expense, and it keeps its claimant id.
- **Gotchas:** after jumping the test clock days ahead the old access token is expired too, so tests mint a fresh one. `RoleMatrixTest` checks 80 cells (16 endpoints × 5 callers) from a table; a MEMBER gets 404 rather than 403 on a colleague's expense because the object itself is hidden.
- **Explain it back (yours to write):** what you built, why `EnumSet` and enums with behaviour fit the role ladder, one thing still unclear.

## M7 · Receipts

- **Decisions:**
  - Added `GET .../receipts` (list with scan status), which the concept did not list: a 202 + PENDING_SCAN upload needs something to poll.
  - Download returns a JSON `{url, expiresAt}` instead of a redirect, so clients decide what to do with the link. The link lives five minutes and the store stays private.
  - `ExpensesFacade` (in expenses) lets receipts ask "may this caller see / change receipts of this expense" and throws the usual 404/403/409 itself. `ReceiptChecks` is implemented by receipts (D3), so expenses never depends on receipts.
  - Upload order: permission check, then stream to a temp file through a `CappedInputStream` and a `DigestInputStream`, then Tika magic-byte detection, then duplicate checks, then store under a random key, then insert the row and publish `ReceiptUploaded`. The database's unique index `(expense_id, sha256)` decides simultaneous identical uploads (tested with a barrier).
  - The scan runs on `@Async @TransactionalEventListener(AFTER_COMMIT)`, with a hand-written ClamAV `INSTREAM` client under `@Retryable`. Infected files move to a `quarantine/` key and are never served. The ClamAV framing is tested against a stand-in `clamd` socket; the S3 adapter is tested against a real SeaweedFS container.
  - **Known gap, on purpose:** if the application dies between the upload commit and the scan (or ClamAV stays down), the receipt stays `PENDING_SCAN` and nothing re-triggers it; `ExpenseDeletedCleanup` has the same hole (orphaned rows/objects). M9's event publication registry fixes both.
  - Test setup: a directory-backed `ReceiptStorage` and a scanner that spots the EICAR string (assembled from pieces, so this repository is not flagged by antivirus tools) replace S3 and ClamAV in the `test` profile. `FakeReceiptChecks` is gone: expense tests seed `ACCEPTED` receipts.
- **Gotchas:**
  - Tomcat closes the connection after an oversized upload unless it may swallow the rest of the body, so the client saw "broken pipe" instead of 413. `server.tomcat.max-swallow-size=16MB` (just above the 11 MB request cap) fixes that without letting huge bodies through.
  - The generator only checks the multipart part exists; type, size and duplicate rules are all enforced in code.
  - **Build:** the compiler `--add-exports` flags for Error Prone now live in `.mvn/jvm.config` (the documented way) instead of forking javac.
  - **A confusing failure, and what it was (see M8):** now and then the app failed to start with `NoClassDefFoundError: <some class>` and class files containing type names without their package. It looked like a Maven or Error Prone bug but was the VS Code Java language server rebuilding `target/classes` with its own compiler while I edited files.
- **Explain it back (yours to write):** what you built, why `InputStream`, try-with-resources and `MessageDigest` shaped the upload, one thing still unclear.

## M8 · Batch work

- **Language study first:** `src/test/java/com/ledgerly/study/ConcurrencyStudyTest` has small experiments on `ExecutorService`, virtual threads, `CompletableFuture`, a lost-update race that is caused on purpose and then fixed three ways (`synchronized`, a lock, an atomic), and `ScopedValue` (final in JDK 25) against `ThreadLocal`. The M6 last-OWNER bug was the same read-decide-write shape.
- **Decisions:**
  - `ExpensesFacade` (D4) got `validate(NewExpense)` and `createDrafts(team, claimant, list)`. `createDrafts` is all-or-nothing in one transaction and is the only way other modules create expenses; batch and import both use it. `Category` moved to the module's public package because `NewExpense` needs it.
  - Batch: the item schema in the spec has no length or format constraints on purpose, so one bad item becomes that item's `REJECTED` result instead of a 400 for the whole request. Structural problems (an unknown field, a non-string amount) are still a 400. `atomic=true` answers 422 `BATCH_REJECTED` with one `errors` entry per problem, named `items[<index>].<field>`.
  - The object-store port moved to `shared.storage.ObjectStorage` (config under `ledgerly.storage.s3.*`); receipts use the `receipts/` prefix, imports `imports/`.
  - Import job: `FlatFileItemReader` (one physical line is one record, so a stray quote cannot swallow the file and line numbers stay true) feeds `RowProcessor`, which builds a `NewExpense` through `Money`, `Category` and the expense field checks; bad lines throw a skippable `RowRejected`. A `SkipListener` writes `import_row_errors` (idempotent via its primary key, which matters on restart). The writer calls `createDrafts` per chunk of 100 and bumps `imported_rows` in the same transaction. The job runs on a virtual-thread executor, started after the upload commits.
  - Spring Batch's metadata tables are copied from `spring-batch-core` into `V6__imports.sql` (not `initialize-schema`), so Flyway owns the schema.
  - Cheap refusals happen before a job exists: not a teammate, daily limit (429 + `Retry-After` until UTC midnight, "today" from the `Clock`), size (413), not UTF-8 text or binary (415), wrong header or too many rows (400). The limit is enforced under a Postgres advisory lock, so eight simultaneous uploads let exactly five through (tested). Refused uploads do not use up the allowance.
  - CSV export (D7) streams: a lazy `InputStream` pulls 500-row pages, each in its own short read-only transaction, up to `max-export-rows` (10,000). `X-Total-Count` and `X-Truncated` tell the client when it was capped. Cells starting with `=`, `+`, `-`, `@`, tab or CR get a leading quote. Imported formula cells are stored as typed and neutralised only on export.
  - JSON bodies are capped at 1 MB by `JsonBodyLimitFilter`, which runs before Spring Security: a declared `Content-Length` over the limit is a 413 without reading the body, and a chunked body is counted while it is read. Tomcat only limits form posts and multipart uploads.
  - Restart: a failure in the writer rolls the whole chunk back, leaves earlier chunks committed, and marks the import `FAILED`. `ImportRunner.restart(importId)` resumes from the last committed chunk with no duplicates (tested with a facade that fails once). There is no HTTP endpoint for it; it is for a future admin tool.
  - **Known gap, for M9:** if the app dies between the upload commit and the job start, the import stays `QUEUED`; nothing restarts a `FAILED` import automatically.
- **Gotchas:**
  - The Apache HttpClient on the test classpath (via Testcontainers) honours `Retry-After` on a 429 and waited about 13 hours before repeating a request, so the 6th-import test seemed to hang. The test client now uses the JDK's `HttpClient`, which does not retry. The server was right all along.
  - **The build "corruption" of M7 was the IDE, not Maven.** The VS Code Java language server compiles into the same `target/classes`. After I changed a file, the class files were rewritten a second or two later with no Maven running, sometimes while sources were half-edited, which leaves types without their package. If `./mvnw` ever shows `NoClassDefFoundError` for one of our own classes, run `./mvnw clean verify`; to stop it for good, set `"java.autobuild.enabled": false` in `.vscode/settings.json` (or close the Java extension while building).
  - Spring Batch 6 moved packages (`org.springframework.batch.infrastructure.item.*`, `...core.job.*`); the classic `StepBuilder.chunk(size, txManager)` still works and is what the fault-tolerant skip configuration needs.
- **Explain it back (yours to write):** what you built, when a virtual thread helps and when it does not, one thing still unclear.

## M9 · Events and audit

- **What changed under the hood:** `spring-modulith-starter-jpa` adds the event publication registry (`V7__event_publication.sql`, copied from Modulith's own schema). For every transactional listener of an event a row is written *in the publisher's transaction* and completed when the listener succeeds; `republish-outstanding-events-on-restart: true` delivers the leftovers at start-up, and `EventMaintenance` re-delivers events stuck for more than two minutes (a scanner that was down) and deletes completed publications after a week.
- **Observer pattern, made durable:** plain `@TransactionalEventListener(AFTER_COMMIT)` loses the event if the application dies right after the commit (the M7 weakness). `@ApplicationModuleListener` = `@Async` + its own transaction + the registry. `EventRegistryRecoveryTest` shows the M7 scenario recovering: the scanner is down, the receipt waits as `PENDING_SCAN` with an open publication, then re-delivery makes it `ACCEPTED` and closes the publication.
- **A listener that fails must throw.** `ScanReceipt` used to catch the scanner error and log it, which now would have marked the event as handled. It rethrows. Idempotence is what makes re-delivery safe, so scanning an already scanned receipt, starting an already finished import and recording an already recorded event all do nothing.
- **Not every listener should be tracked.** The verification token travels in an internal event handled by a plain `@EventListener`, which the registry does not store; a test asserts the token appears neither in `event_publication` nor in the audit trail.
- **Imports:** `ImportRunner` is a tracked `@Async @TransactionalEventListener`, not an `@ApplicationModuleListener`, because that annotation wraps the method in one transaction and the whole job would have become a single transaction, ending the chunk-by-chunk commits that make restarts work. A queued import that was never started is now started after a restart. Still open: a job that was *running* when the application died is left alone (Spring Batch would first need that execution marked as stopped).
- **Event shape:** each module has a sealed interface (`IdentityEvent`, `TeamEvent`, `ExpenseEvent`, `ReceiptEvent`; `ImportCompleted` is a single record) with `eventId`, the actor and `occurredAt`. Every record has a canonical constructor that takes the event id (what JSON needs) and a convenience one that generates it. Events carry ids and field names only: no amounts, merchants, notes, rejection reasons or emails (ADR 0001).
- **The audit module** depends only on the `events` packages of the others (never on facades). It has 25 listeners, one per event type, each writing ids and field names. `audit_events` is append-only twice over: the repository has no update or delete method (a test checks the public methods), and the database refuses it: the privileges are revoked from everyone including the owner, and triggers raise on `UPDATE`, `DELETE` and `TRUNCATE`. The triggers matter because a superuser (the test database user) ignores privileges.
- **Paging:** the trail is paged by `(created_at, id)` with the M4 cursor, where `created_at` is when the row was *written*, not when the event happened: rows are only added at the end, so a slow listener can never slip a row into the middle of a page a client has already fetched. `occurredAt` is shown to the client.
- **Team-less events** (registering, verifying, refresh-token reuse) are stored with `team_id NULL`; the team query is `team_id = :team`, so they never show up, even for a team whose owner is that very user (tested).
- **Authorization** of `GET /teams/{teamId}/audit-events` uses `@PreAuthorize("@teamAuthorization…")` by bean name, so audit has no code dependency on teams.
- **Tests worth reading:** `EveryEventReachesTheAuditTrailTest` compares a sample of each event with `getPermittedSubclasses()` of every sealed interface, so adding an event without an audit listener fails the build. `AuditModuleTest` uses `@ApplicationModuleTest` and the `Scenario` API to run the audit module on its own. `ModuleDocumentationTest` writes the generated diagrams to `docs/modules/`.
- **Gotchas:** `@ApplicationModuleTest` starts only the module under test, so anything registered on the application class (like the `LedgerlyLimits` properties) is missing; it now lives in `shared`, and the test names `extraIncludes = "shared"`.
- **Explain it back (yours to write):** what you built, the observer pattern and why a sealed event type helps, one thing still unclear.
