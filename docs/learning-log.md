# Learning log

## M0 · Plain-Java core

- **Currency rule:** `Money` accepts any `java.util.Currency` whose `getDefaultFractionDigits()` is ≥ 0. Pseudo codes (XAU, XTS, XXX) report -1 and are rejected. `Currency` has no "active" flag, so withdrawn historical codes are still accepted.
- **Explain it back (yours to write):** what you built, why `Money` is a record and `Expense` is not, one thing still unclear.
