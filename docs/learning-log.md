# Learning log

## M0 · Plain-Java core

- **Currency rule:** `Money` accepts any `java.util.Currency` whose `getDefaultFractionDigits()` is ≥ 0. Pseudo codes (XAU, XTS, XXX) report -1 and are rejected. `Currency` has no "active" flag, so withdrawn historical codes are still accepted.
- **Explain it back (yours to write):** what you built, why `Money` is a record and `Expense` is not, one thing still unclear.

## M1 · First Boot app

- **Tooling notes:** Boot 4.1.2 does not manage Spring Modulith, so its BOM is imported (2.1.2). `@NullMarked` applies per package, so every package needs a `package-info.java`; NullAway runs on main sources only. Docker Compose support is skipped in tests by default, so tests need no Docker yet.
- **Not verified here:** the OTLP traces and logs reaching Grafana (`docker compose up`, open http://localhost:3000). Run the app with `SPRING_PROFILES_ACTIVE=local` and check.
- **Explain it back (yours to write):** what you built, why Spring needed records / annotations / reflection for it, one thing still unclear.
