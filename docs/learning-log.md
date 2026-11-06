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
