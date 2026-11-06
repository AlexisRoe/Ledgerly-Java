package com.ledgerly.expenses.internal.application;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.Expense;
import com.ledgerly.expenses.internal.infrastructure.ExpenseRepository;
import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.Unprocessable;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.idempotency.IdempotencyStore;
import com.ledgerly.shared.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a draft expense for the caller. With an idempotency key, a repeated request returns the first result. */
@Service
public class CreateExpense {

    /** Input: the claimant is the caller; {@code note} is optional. */
    public record Command(
            TeamId teamId,
            UserId claimantId,
            Money money,
            Category category,
            LocalDate incurredOn,
            String merchant,
            @Nullable String note) {}

    /** {@code replayed} is true when an earlier request with the same idempotency key already created it. */
    public record Result(ExpenseView view, boolean replayed) {}

    private final ExpenseRepository expenses;
    private final ExpenseAccess access;
    private final IdempotencyStore idempotency;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CreateExpense(
            ExpenseRepository expenses,
            ExpenseAccess access,
            IdempotencyStore idempotency,
            ApplicationEventPublisher events,
            Clock clock) {
        this.expenses = expenses;
        this.access = access;
        this.idempotency = idempotency;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates the draft and publishes {@code ExpenseEvent.Created}.
     *
     * @param idempotencyKey optional; the same key with a different body is a conflict
     */
    @Transactional
    public Result execute(Command c, @Nullable String idempotencyKey) {
        access.actor(c.teamId(), c.claimantId());
        String hash = hash(c);
        if (idempotencyKey != null) {
            var previous = idempotency.find(c.claimantId(), idempotencyKey);
            if (previous.isPresent()) {
                return replay(c, previous.get(), hash);
            }
        }
        Expense expense = Expense.draft(
                c.teamId(), c.claimantId(), c.money(), c.category(), c.incurredOn(), c.merchant(), c.note(), clock);
        ExpenseView view = ExpenseView.of(expenses.save(expense));
        events.publishEvent(new ExpenseEvent.Created(c.teamId(), view.id(), c.claimantId(), clock.instant()));
        if (idempotencyKey != null) {
            try {
                idempotency.save(
                        c.claimantId(),
                        idempotencyKey,
                        new IdempotencyStore.Entry(hash, 201, view.id().value()));
            } catch (DuplicateKeyException e) {
                throw new Conflict(
                        ErrorCatalogue.IDEMPOTENCY_IN_PROGRESS, "A request with this Idempotency-Key is in progress");
            }
        }
        return new Result(view, false);
    }

    private Result replay(Command c, IdempotencyStore.Entry previous, String hash) {
        if (!previous.requestHash().equals(hash)) {
            throw new Unprocessable(
                    ErrorCatalogue.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used with a different request");
        }
        return expenses.findByTeamIdAndId(c.teamId(), new ExpenseId(previous.resourceId()))
                .map(e -> new Result(ExpenseView.of(e), true))
                .orElseThrow(() -> new Conflict(
                        ErrorCatalogue.IDEMPOTENCY_IN_PROGRESS, "The expense created by this key no longer exists"));
    }

    private static String hash(Command c) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(c.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
