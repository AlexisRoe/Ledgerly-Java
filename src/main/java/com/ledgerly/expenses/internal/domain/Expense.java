package com.ledgerly.expenses.internal.domain;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.ReceiptSummary;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import com.ledgerly.shared.role.Role;
import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A single amount spent on behalf of a team. It has an identity (its id) and changes over time, so it
 * is a class, not a record: two expenses with the same field values are still different expenses.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    public static final int MAX_MERCHANT = 200;
    public static final int MAX_NOTE = 2000;
    public static final int MAX_REASON = 1000;

    @EmbeddedId
    private ExpenseId id;

    private TeamId teamId;
    private UserId claimantId;

    @Embedded
    private Money money;

    @Enumerated(EnumType.STRING)
    private Category category;

    private LocalDate incurredOn;
    private String merchant;
    private @Nullable String note;

    @Enumerated(EnumType.STRING)
    private ExpenseStatus status;

    @ElementCollection
    @CollectionTable(name = "expense_rejections", joinColumns = @JoinColumn(name = "expense_id"))
    @OrderColumn(name = "position")
    private List<Rejection> rejections = new ArrayList<>();

    @Version
    private long version;

    @CreationTimestamp
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    private @Nullable Instant updatedAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Expense() {}

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

    /**
     * Runs the field rules of a new expense (merchant required and capped, note capped, date not in the future)
     * without creating anything, so batch and import can check a row before they write it.
     *
     * @throws InvalidInput naming the first field that breaks a rule
     */
    public static void checkFields(
            LocalDate incurredOn, @Nullable String merchant, @Nullable String note, Clock clock) {
        validMerchant(merchant);
        validNote(note);
        validDate(incurredOn, clock);
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
            throw new InvalidInput("reason", "A rejection reason is required");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON) {
            throw new InvalidInput("reason", "Rejection reason is longer than " + MAX_REASON);
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
            throw new NotDraft(status);
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

    /** Throws unless the actor may add or remove receipts: the claimant, while it is a draft. */
    public void requireCanChangeReceiptsBy(Actor actor) {
        if (!actor.id().equals(claimantId)) {
            throw new NotAllowed("Only the claimant may change receipts");
        }
        if (status != ExpenseStatus.DRAFT) {
            throw new NotDraft(status);
        }
    }

    public boolean canAddOrRemoveReceipts(Actor actor) {
        return isDraftOf(actor);
    }

    /** Throws unless the actor may delete this expense: the claimant, while it is a draft. */
    public void requireDeletableBy(Actor actor) {
        if (!actor.id().equals(claimantId)) {
            throw new NotAllowed("Only the claimant may delete an expense");
        }
        if (status != ExpenseStatus.DRAFT) {
            throw new NotDraft(status);
        }
    }

    public boolean canDelete(Actor actor) {
        return isDraftOf(actor);
    }

    public boolean isVisibleTo(Actor actor) {
        return actor.id().equals(claimantId) || actor.role().includes(Role.APPROVER);
    }

    // --- accessors -------------------------------------------------------------------------

    public long version() {
        return version;
    }

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
            throw new InvalidInput("merchant", "Merchant is required");
        }
        String trimmed = merchant.strip();
        if (trimmed.length() > MAX_MERCHANT) {
            throw new InvalidInput("merchant", "Merchant is longer than " + MAX_MERCHANT);
        }
        return trimmed;
    }

    private static @Nullable String validNote(@Nullable String note) {
        if (note == null) {
            return null;
        }
        if (note.length() > MAX_NOTE) {
            throw new InvalidInput("note", "Note is longer than " + MAX_NOTE);
        }
        return note;
    }

    private static void validDate(LocalDate incurredOn, Clock clock) {
        if (incurredOn.isAfter(LocalDate.now(clock))) {
            throw new InvalidInput("incurredOn", "incurredOn must not be in the future");
        }
    }
}
