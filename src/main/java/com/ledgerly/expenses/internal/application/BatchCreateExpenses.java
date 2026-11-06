package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.expenses.ValidationProblem;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.FieldViolation;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.error.Unprocessable;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Creates up to 100 draft expenses in one call. Every item is checked first; then either the valid ones are
 * created (partial success, the default) or, with {@code atomic}, a single bad item rejects the whole batch.
 * The writing itself goes through {@link ExpensesFacade#createDrafts}, the same entry point imports use (D4).
 */
@Service
public class BatchCreateExpenses {

    /** One item of the request, already parsed as far as the web layer could. */
    public sealed interface Candidate permits Candidate.Ready, Candidate.Unparseable {
        record Ready(NewExpense expense) implements Candidate {}

        /** The item could not even be turned into a {@link NewExpense} (for example an amount that is not a number). */
        record Unparseable(String field, String message) implements Candidate {}
    }

    public record Problem(String code, @Nullable String field, String message) {}

    public record ItemResult(
            int index, @Nullable UUID id, @Nullable Problem problem) {}

    public record Result(List<ItemResult> results) {
        public long created() {
            return results.stream().filter(r -> r.id() != null).count();
        }

        public long rejected() {
            return results.size() - created();
        }
    }

    private final ExpenseAccess access;
    private final ExpensesFacade facade;
    private final LedgerlyLimits limits;

    BatchCreateExpenses(ExpenseAccess access, ExpensesFacade facade, LedgerlyLimits limits) {
        this.access = access;
        this.facade = facade;
        this.limits = limits;
    }

    public Result execute(TeamId teamId, UserId caller, List<Candidate> items, boolean atomic) {
        access.actor(teamId, caller); // not a teammate: 404, before looking at anything else
        if (items.isEmpty() || items.size() > limits.maxBatchSize()) {
            throw new InvalidRequest("items", "Between 1 and " + limits.maxBatchSize() + " items per batch");
        }

        // Check every item first; nothing is written yet.
        List<@Nullable Problem> problems = new ArrayList<>(items.size());
        for (Candidate item : items) {
            problems.add(check(item));
        }

        if (atomic && problems.stream().anyMatch(p -> p != null)) {
            List<FieldViolation> violations = new ArrayList<>();
            for (int i = 0; i < problems.size(); i++) {
                Problem p = problems.get(i);
                if (p != null) {
                    violations.add(new FieldViolation(
                            "items[" + i + "]" + (p.field() == null ? "" : "." + p.field()), p.message()));
                }
            }
            throw new Unprocessable(
                    ErrorCatalogue.BATCH_REJECTED, "Nothing was created because some items are invalid", violations);
        }

        List<NewExpense> valid = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (problems.get(i) == null && items.get(i) instanceof Candidate.Ready ready) {
                valid.add(ready.expense());
            }
        }
        List<com.ledgerly.shared.id.ExpenseId> ids =
                valid.isEmpty() ? List.of() : facade.createDrafts(teamId, caller, valid);

        List<ItemResult> results = new ArrayList<>(items.size());
        int next = 0;
        for (int i = 0; i < items.size(); i++) {
            Problem problem = problems.get(i);
            results.add(
                    problem == null
                            ? new ItemResult(i, ids.get(next++).value(), null)
                            : new ItemResult(i, null, problem));
        }
        return new Result(results);
    }

    private @Nullable Problem check(Candidate item) {
        return switch (item) {
            case Candidate.Unparseable u ->
                new Problem(ErrorCatalogue.VALIDATION_FAILED.code(), u.field(), u.message());
            case Candidate.Ready r -> {
                Optional<ValidationProblem> problem = facade.validate(r.expense());
                yield problem.map(p -> new Problem(ErrorCatalogue.VALIDATION_FAILED.code(), p.field(), p.message()))
                        .orElse(null);
            }
        };
    }
}
