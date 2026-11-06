package com.ledgerly.expenses.internal.domain;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import com.ledgerly.shared.role.Role;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A single amount spent on behalf of a team. It has an identity (its id) and changes over time, so it
 * is a class, not a record: two expenses with the same field values are still different expenses.
 */
public final class Expense {

    public static final int MAX_MERCHANT = 200;
    public static final int MAX_NOTE = 2000;
    public static final int MAX_REASON = 1000;

    private final ExpenseId id;
    private final TeamId teamId;
    private final UserId claimantId;
    private Money money;
    private Category category;
    private LocalDate incurredOn;
    private String merchant;
    private @Nullable String note;
    private ExpenseStatus status;
    private final List<Rejection> rejections = new ArrayList<>();

    private Expense(
            ExpenseId id,
            TeamId teamId,
            UserId claimantId,
            Money money,
            Category category,
            LocalDate incurredOn,
            String merchant,
            @Nullable String note) {
        this.id = id;
        this.teamId = teamId;
        this.claimantId = claimantId;
        this.money = money;
        this.category = category;
        this.incurredOn = incurredOn;
        this.merchant = merchant;
        this.note = note;
        this.status = ExpenseStatus.DRAFT;
    }

    public static Expense draft(
            TeamId teamId,
            UserId claimantId,
            Money money,
            Category category,
            LocalDate incurredOn,
            String merchant,
            @Nullable String note,
            Clock clock) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(claimantId, "claimantId");
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(incurredOn, "incurredOn");
        String cleanMerchant = validMerchant(merchant);
        String cleanNote = validNote(note);
        validDate(incurredOn, clock);
        return new Expense(
                ExpenseId.random(), teamId, claimantId, money, category, incurredOn, cleanMerchant, cleanNote);
    }

    // --- transitions -----------------------------------------------------------------------

    public void submit(Actor actor, ReceiptSummary receipts) {
        requireClaimant(actor, Action.SUBMIT);
        requireStatus(ExpenseStatus.DRAFT, Action.SUBMIT);
        if (receipts.accepted() < 1) {
            throw new RuleViolated("At least one accepted receipt is required to submit");
        }
        if (receipts.pendingScan() > 0) {
            throw new RuleViolated("Receipts are still being scanned");
        }
        status = ExpenseStatus.SUBMITTED;
    }

    public void withdraw(Actor actor) {
        requireClaimant(actor, Action.WITHDRAW);
        requireStatus(ExpenseStatus.SUBMITTED, Action.WITHDRAW);
        status = ExpenseStatus.DRAFT;
    }

    public void approve(Actor actor) {
        requireDecider(actor, Role.APPROVER, Action.APPROVE);
        requireStatus(ExpenseStatus.SUBMITTED, Action.APPROVE);
        status = ExpenseStatus.APPROVED;
    }

    public void reject(Actor actor, @Nullable String reason) {
        requireDecider(actor, Role.APPROVER, Action.REJECT);
        requireStatus(ExpenseStatus.SUBMITTED, Action.REJECT);
        if (reason == null || reason.isBlank()) {
            throw new RuleViolated("A rejection reason is required");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON) {
            throw new RuleViolated("Rejection reason is longer than " + MAX_REASON);
        }
        rejections.add(new Rejection(actor.id(), trimmed));
        status = ExpenseStatus.REJECTED;
    }

    public void reopen(Actor actor) {
        requireClaimant(actor, Action.REOPEN);
        requireStatus(ExpenseStatus.REJECTED, Action.REOPEN);
        status = ExpenseStatus.DRAFT;
    }

    public void reimburse(Actor actor) {
        requireDecider(actor, Role.OWNER, Action.REIMBURSE);
        requireStatus(ExpenseStatus.APPROVED, Action.REIMBURSE);
        status = ExpenseStatus.REIMBURSED;
    }

    // --- draft-only operations -------------------------------------------------------------

    public void edit(
            Actor actor,
            Money money,
            Category category,
            LocalDate incurredOn,
            String merchant,
            @Nullable String note,
            Clock clock) {
        if (!actor.id().equals(claimantId)) {
            throw new NotAllowed("Only the claimant may edit an expense");
        }
        if (status != ExpenseStatus.DRAFT) {
            throw new RuleViolated("Only a draft can be edited, but the expense is " + status);
        }
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(incurredOn, "incurredOn");
        String cleanMerchant = validMerchant(merchant);
        String cleanNote = validNote(note);
        validDate(incurredOn, clock);
        this.money = money;
        this.category = category;
        this.incurredOn = incurredOn;
        this.merchant = cleanMerchant;
        this.note = cleanNote;
    }

    public boolean canAddOrRemoveReceipts(Actor actor) {
        return isDraftOf(actor);
    }

    public boolean canDelete(Actor actor) {
        return isDraftOf(actor);
    }

    public boolean isVisibleTo(Actor actor) {
        return actor.id().equals(claimantId) || actor.role().includes(Role.APPROVER);
    }

    // --- accessors -------------------------------------------------------------------------

    public ExpenseId id() {
        return id;
    }

    public TeamId teamId() {
        return teamId;
    }

    public UserId claimantId() {
        return claimantId;
    }

    public Money money() {
        return money;
    }

    public Category category() {
        return category;
    }

    public LocalDate incurredOn() {
        return incurredOn;
    }

    public String merchant() {
        return merchant;
    }

    public @Nullable String note() {
        return note;
    }

    public ExpenseStatus status() {
        return status;
    }

    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }

    // --- helpers ---------------------------------------------------------------------------

    private boolean isDraftOf(Actor actor) {
        return status == ExpenseStatus.DRAFT && actor.id().equals(claimantId);
    }

    private void requireClaimant(Actor actor, Action action) {
        if (!actor.id().equals(claimantId)) {
            throw new NotAllowed("Only the claimant may " + action);
        }
    }

    private void requireDecider(Actor actor, Role minimum, Action action) {
        if (!actor.role().includes(minimum)) {
            throw new NotAllowed("Role " + actor.role() + " may not " + action);
        }
        if (actor.id().equals(claimantId)) {
            throw new SelfDealing(action);
        }
    }

    private void requireStatus(ExpenseStatus expected, Action action) {
        if (status != expected) {
            throw new InvalidTransition(status, action);
        }
    }

    private static String validMerchant(@Nullable String merchant) {
        if (merchant == null || merchant.isBlank()) {
            throw new RuleViolated("Merchant is required");
        }
        String trimmed = merchant.strip();
        if (trimmed.length() > MAX_MERCHANT) {
            throw new RuleViolated("Merchant is longer than " + MAX_MERCHANT);
        }
        return trimmed;
    }

    private static @Nullable String validNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        if (note.length() > MAX_NOTE) {
            throw new RuleViolated("Note is longer than " + MAX_NOTE);
        }
        return note;
    }

    private static void validDate(LocalDate incurredOn, Clock clock) {
        if (incurredOn.isAfter(LocalDate.now(clock))) {
            throw new RuleViolated("incurredOn must not be in the future");
        }
    }
}
