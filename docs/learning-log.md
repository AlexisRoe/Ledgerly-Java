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
