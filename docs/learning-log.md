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
  - **Build:** Error Prone sharing the real compile occasionally produced class files with unresolved type names (`NoClassDefFoundError: Action` at start-up), and once corrupt they survived incremental builds. NullAway now runs as a separate `nullaway` compiler execution into `target/nullaway-classes`, so the classes the app runs with always come from plain javac; a null violation still fails `verify`. The compiler `--add-exports` flags moved to `.mvn/jvm.config`.
- **Explain it back (yours to write):** what you built, why `InputStream`, try-with-resources and `MessageDigest` shaped the upload, one thing still unclear.
