package com.ledgerly.expenses.internal.web;

import com.ledgerly.expenses.internal.application.CreateExpense;
import com.ledgerly.expenses.internal.application.DeleteExpense;
import com.ledgerly.expenses.internal.application.ExpenseView;
import com.ledgerly.expenses.internal.application.GetExpense;
import com.ledgerly.expenses.internal.application.TransitionExpense;
import com.ledgerly.expenses.internal.application.UpdateExpense;
import com.ledgerly.expenses.internal.domain.Action;
import com.ledgerly.expenses.internal.web.generated.ExpensesApi;
import com.ledgerly.expenses.internal.web.generated.model.CreateExpenseRequest;
import com.ledgerly.expenses.internal.web.generated.model.ExpenseResponse;
import com.ledgerly.expenses.internal.web.generated.model.RejectRequest;
import com.ledgerly.expenses.internal.web.generated.model.UpdateExpenseRequest;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.security.CurrentUser;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ExpensesController implements ExpensesApi {

    private final CurrentUser currentUser;
    private final CreateExpense create;
    private final GetExpense get;
    private final UpdateExpense update;
    private final DeleteExpense delete;
    private final TransitionExpense transition;

    ExpensesController(
            CurrentUser currentUser,
            CreateExpense create,
            GetExpense get,
            UpdateExpense update,
            DeleteExpense delete,
            TransitionExpense transition) {
        this.currentUser = currentUser;
        this.create = create;
        this.get = get;
        this.update = update;
        this.delete = delete;
        this.transition = transition;
    }

    @Override
    public ResponseEntity<ExpenseResponse> createExpense(
            UUID teamId, CreateExpenseRequest request, @Nullable String idempotencyKey) {
        UserId caller = currentUser.id();
        var command = new CreateExpense.Command(
                new TeamId(teamId),
                caller,
                ExpenseMapper.toMoney(request.getMoney(), "money"),
                ExpenseMapper.toDomain(request.getCategory()),
                request.getIncurredOn(),
                request.getMerchant(),
                request.getNote());
        var result = create.execute(command, idempotencyKey);
        ExpenseView view = result.view();
        URI location = URI.create("/api/v1/teams/" + teamId + "/expenses/" + view.id());
        return ResponseEntity.created(location).eTag(etag(view)).body(ExpenseMapper.toResponse(view));
    }

    @Override
    public ResponseEntity<ExpenseResponse> getExpense(UUID teamId, UUID expenseId) {
        return ok(get.execute(new TeamId(teamId), new ExpenseId(expenseId), currentUser.id()));
    }

    @Override
    public ResponseEntity<ExpenseResponse> updateExpense(
            String ifMatch, UUID teamId, UUID expenseId, UpdateExpenseRequest request) {
        var command = new UpdateExpense.Command(
                new TeamId(teamId),
                new ExpenseId(expenseId),
                currentUser.id(),
                parseVersion(ifMatch),
                request.getMoney() == null ? null : ExpenseMapper.toMoney(request.getMoney(), "money"),
                request.getCategory() == null ? null : ExpenseMapper.toDomain(request.getCategory()),
                request.getIncurredOn(),
                request.getMerchant(),
                request.getNote());
        return ok(update.execute(command));
    }

    @Override
    public ResponseEntity<Void> deleteExpense(String ifMatch, UUID teamId, UUID expenseId) {
        delete.execute(new TeamId(teamId), new ExpenseId(expenseId), currentUser.id(), parseVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ExpenseResponse> submitExpense(UUID teamId, UUID expenseId) {
        return act(Action.SUBMIT, teamId, expenseId, null);
    }

    @Override
    public ResponseEntity<ExpenseResponse> withdrawExpense(UUID teamId, UUID expenseId) {
        return act(Action.WITHDRAW, teamId, expenseId, null);
    }

    @Override
    public ResponseEntity<ExpenseResponse> approveExpense(UUID teamId, UUID expenseId) {
        return act(Action.APPROVE, teamId, expenseId, null);
    }

    @Override
    public ResponseEntity<ExpenseResponse> rejectExpense(UUID teamId, UUID expenseId, RejectRequest request) {
        return act(Action.REJECT, teamId, expenseId, request.getReason());
    }

    @Override
    public ResponseEntity<ExpenseResponse> reopenExpense(UUID teamId, UUID expenseId) {
        return act(Action.REOPEN, teamId, expenseId, null);
    }

    @Override
    public ResponseEntity<ExpenseResponse> reimburseExpense(UUID teamId, UUID expenseId) {
        return act(Action.REIMBURSE, teamId, expenseId, null);
    }

    private ResponseEntity<ExpenseResponse> act(Action action, UUID teamId, UUID expenseId, @Nullable String reason) {
        return ok(transition.execute(action, new TeamId(teamId), new ExpenseId(expenseId), currentUser.id(), reason));
    }

    private static ResponseEntity<ExpenseResponse> ok(ExpenseView view) {
        return ResponseEntity.ok().eTag(etag(view)).body(ExpenseMapper.toResponse(view));
    }

    private static String etag(ExpenseView view) {
        return "\"" + view.version() + "\"";
    }

    /** Accepts {@code "3"} and {@code W/"3"}. Anything else cannot match a version, so it counts as stale. */
    private static long parseVersion(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PreconditionFailed();
        }
    }
}
