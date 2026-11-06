package com.ledgerly.expenses.internal.web;

import com.ledgerly.expenses.internal.domain.ExpenseException;
import com.ledgerly.expenses.internal.domain.InvalidInput;
import com.ledgerly.expenses.internal.domain.InvalidTransition;
import com.ledgerly.expenses.internal.domain.NotAllowed;
import com.ledgerly.expenses.internal.domain.NotDraft;
import com.ledgerly.expenses.internal.domain.RuleViolated;
import com.ledgerly.expenses.internal.domain.SelfDealing;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.FieldViolation;
import com.ledgerly.shared.error.ProblemFactory;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the expense domain's refusals to the shared error catalogue. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ExpenseDomainExceptionHandler {

    private final ProblemFactory problems;

    ExpenseDomainExceptionHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ExpenseException.class)
    ResponseEntity<ProblemDetail> handle(ExpenseException e) {
        // ExpenseException is sealed, so this switch must cover every case.
        ProblemDetail problem = switch (e) {
            case InvalidTransition t ->
                problems.create(ErrorCatalogue.EXPENSE_INVALID_TRANSITION, t.getMessage(), List.of());
            case SelfDealing s -> problems.create(ErrorCatalogue.EXPENSE_SELF_DEALING, s.getMessage(), List.of());
            case NotDraft d -> problems.create(ErrorCatalogue.EXPENSE_NOT_DRAFT, d.getMessage(), List.of());
            case NotAllowed n -> problems.create(ErrorCatalogue.FORBIDDEN, n.getMessage(), List.of());
            case RuleViolated r -> problems.create(ErrorCatalogue.EXPENSE_RULE_VIOLATED, r.getMessage(), List.of());
            case InvalidInput i ->
                problems.create(
                        ErrorCatalogue.VALIDATION_FAILED,
                        "Request validation failed",
                        List.of(new FieldViolation(i.field(), String.valueOf(i.getMessage()))));
        };
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
