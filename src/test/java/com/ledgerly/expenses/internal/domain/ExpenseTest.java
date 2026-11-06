package com.ledgerly.expenses.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import com.ledgerly.shared.role.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExpenseTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    static final ReceiptSummary READY = new ReceiptSummary(1, 0);

    static final UserId CLAIMANT_ID = UserId.random();
    static final Actor CLAIMANT = new Actor(CLAIMANT_ID, Role.MEMBER);
    static final Actor APPROVER = new Actor(UserId.random(), Role.APPROVER);
    static final Actor OWNER = new Actor(UserId.random(), Role.OWNER);
    static final Actor OTHER_MEMBER = new Actor(UserId.random(), Role.MEMBER);

    static Expense draft() {
        return Expense.draft(
                TeamId.random(),
                CLAIMANT_ID,
                Money.of("12.50", "EUR"),
                Category.MEALS,
                TODAY,
                "Cafe Central",
                "Lunch",
                CLOCK);
    }

    static Expense inStatus(ExpenseStatus target) {
        Expense e = draft();
        if (target == ExpenseStatus.DRAFT) return e;
        e.submit(CLAIMANT, READY);
        if (target == ExpenseStatus.SUBMITTED) return e;
        if (target == ExpenseStatus.REJECTED) {
            e.reject(APPROVER, "no receipt for tip");
            return e;
        }
        e.approve(APPROVER);
        if (target == ExpenseStatus.APPROVED) return e;
        e.reimburse(OWNER);
        return e;
    }

    @Nested
    class Creation {

        @Test
        void startsAsDraft() {
            Expense e = draft();
            assertThat(e.status()).isEqualTo(ExpenseStatus.DRAFT);
            assertThat(e.id()).isNotNull();
            assertThat(e.claimantId()).isEqualTo(CLAIMANT_ID);
            assertThat(e.rejections()).isEmpty();
        }

        @Test
        void futureDateIsRejected() {
            assertThatThrownBy(() -> Expense.draft(
                            TeamId.random(),
                            CLAIMANT_ID,
                            Money.of("1", "EUR"),
                            Category.OTHER,
                            TODAY.plusDays(1),
                            "x",
                            null,
                            CLOCK))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void todayIsAllowed() {
            assertThat(draft().incurredOn()).isEqualTo(TODAY);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void merchantIsRequired(String merchant) {
            assertThatThrownBy(() -> Expense.draft(
                            TeamId.random(),
                            CLAIMANT_ID,
                            Money.of("1", "EUR"),
                            Category.OTHER,
                            TODAY,
                            merchant,
                            null,
                            CLOCK))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void merchantAndNoteAreCapped() {
            assertThatThrownBy(() -> Expense.draft(
                            TeamId.random(),
                            CLAIMANT_ID,
                            Money.of("1", "EUR"),
                            Category.OTHER,
                            TODAY,
                            "m".repeat(Expense.MAX_MERCHANT + 1),
                            null,
                            CLOCK))
                    .isInstanceOf(RuleViolated.class);
            assertThatThrownBy(() -> Expense.draft(
                            TeamId.random(),
                            CLAIMANT_ID,
                            Money.of("1", "EUR"),
                            Category.OTHER,
                            TODAY,
                            "m",
                            "n".repeat(Expense.MAX_NOTE + 1),
                            CLOCK))
                    .isInstanceOf(RuleViolated.class);
        }
    }

    @Nested
    class Transitions {

        /** An actor who is allowed to perform the action. */
        static Actor validActor(Action action) {
            return switch (action) {
                case SUBMIT, WITHDRAW, REOPEN -> CLAIMANT;
                case APPROVE, REJECT -> APPROVER;
                case REIMBURSE -> OWNER;
            };
        }

        static void perform(Expense e, Action action) {
            Actor actor = validActor(action);
            switch (action) {
                case SUBMIT -> e.submit(actor, READY);
                case WITHDRAW -> e.withdraw(actor);
                case APPROVE -> e.approve(actor);
                case REJECT -> e.reject(actor, "reason");
                case REOPEN -> e.reopen(actor);
                case REIMBURSE -> e.reimburse(actor);
            }
        }

        static ExpenseStatus legalTarget(ExpenseStatus from, Action action) {
            return switch (action) {
                case SUBMIT -> from == ExpenseStatus.DRAFT ? ExpenseStatus.SUBMITTED : null;
                case WITHDRAW -> from == ExpenseStatus.SUBMITTED ? ExpenseStatus.DRAFT : null;
                case APPROVE -> from == ExpenseStatus.SUBMITTED ? ExpenseStatus.APPROVED : null;
                case REJECT -> from == ExpenseStatus.SUBMITTED ? ExpenseStatus.REJECTED : null;
                case REOPEN -> from == ExpenseStatus.REJECTED ? ExpenseStatus.DRAFT : null;
                case REIMBURSE -> from == ExpenseStatus.APPROVED ? ExpenseStatus.REIMBURSED : null;
            };
        }

        static Stream<Arguments> allStatusActionPairs() {
            return Stream.of(ExpenseStatus.values())
                    .flatMap(s -> Stream.of(Action.values()).map(a -> Arguments.of(s, a)));
        }

        @ParameterizedTest(name = "{0} + {1}")
        @MethodSource("allStatusActionPairs")
        void legalPairsSucceedAndTheRestThrow(ExpenseStatus from, Action action) {
            Expense e = inStatus(from);
            ExpenseStatus expected = legalTarget(from, action);
            if (expected == null) {
                assertThatThrownBy(() -> perform(e, action)).isInstanceOf(InvalidTransition.class);
                assertThat(e.status()).isEqualTo(from);
            } else {
                perform(e, action);
                assertThat(e.status()).isEqualTo(expected);
            }
        }

        @Test
        void exactlySixLegalPairs() {
            long legal = allStatusActionPairs()
                    .map(a -> legalTarget((ExpenseStatus) a.get()[0], (Action) a.get()[1]))
                    .filter(t -> t != null)
                    .count();
            assertThat(legal).isEqualTo(6);
        }

        @Test
        void onlyTheClaimantMaySubmitWithdrawOrReopen() {
            assertThatThrownBy(() -> inStatus(ExpenseStatus.DRAFT).submit(OWNER, READY))
                    .isInstanceOf(NotAllowed.class);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).withdraw(OWNER))
                    .isInstanceOf(NotAllowed.class);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.REJECTED).reopen(APPROVER))
                    .isInstanceOf(NotAllowed.class);
        }

        @Test
        void memberCannotApproveOrReject() {
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).approve(OTHER_MEMBER))
                    .isInstanceOf(NotAllowed.class);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).reject(OTHER_MEMBER, "no"))
                    .isInstanceOf(NotAllowed.class);
        }

        @Test
        void ownerMayApproveAndReject() {
            Expense a = inStatus(ExpenseStatus.SUBMITTED);
            a.approve(OWNER);
            assertThat(a.status()).isEqualTo(ExpenseStatus.APPROVED);
            Expense r = inStatus(ExpenseStatus.SUBMITTED);
            r.reject(OWNER, "no");
            assertThat(r.status()).isEqualTo(ExpenseStatus.REJECTED);
        }

        @Test
        void onlyOwnerMayReimburse() {
            assertThatThrownBy(() -> inStatus(ExpenseStatus.APPROVED).reimburse(APPROVER))
                    .isInstanceOf(NotAllowed.class);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.APPROVED).reimburse(CLAIMANT))
                    .isInstanceOf(NotAllowed.class);
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"APPROVER", "OWNER"})
        void selfDealingOnApproveAndRejectIsRefused(Role role) {
            Actor self = new Actor(CLAIMANT_ID, role);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).approve(self))
                    .isInstanceOf(SelfDealing.class);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).reject(self, "x"))
                    .isInstanceOf(SelfDealing.class);
        }

        @Test
        void selfDealingOnReimburseIsRefused() {
            Actor self = new Actor(CLAIMANT_ID, Role.OWNER);
            assertThatThrownBy(() -> inStatus(ExpenseStatus.APPROVED).reimburse(self))
                    .isInstanceOf(SelfDealing.class);
        }

        @Test
        void submitNeedsAnAcceptedReceipt() {
            assertThatThrownBy(() -> draft().submit(CLAIMANT, new ReceiptSummary(0, 0)))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void submitNeedsNoPendingScans() {
            assertThatThrownBy(() -> draft().submit(CLAIMANT, new ReceiptSummary(2, 1)))
                    .isInstanceOf(RuleViolated.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        void rejectNeedsAReason(String reason) {
            assertThatThrownBy(() -> inStatus(ExpenseStatus.SUBMITTED).reject(APPROVER, reason))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void rejectReasonIsCapped() {
            assertThatThrownBy(() ->
                            inStatus(ExpenseStatus.SUBMITTED).reject(APPROVER, "r".repeat(Expense.MAX_REASON + 1)))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void everyRejectionIsKept() {
            Expense e = inStatus(ExpenseStatus.SUBMITTED);
            e.reject(APPROVER, "first");
            e.reopen(CLAIMANT);
            e.submit(CLAIMANT, READY);
            e.reject(OWNER, "second");
            assertThat(e.rejections()).extracting(Rejection::reason).containsExactly("first", "second");
            assertThat(e.rejections().getLast().rejectedBy()).isEqualTo(OWNER.id());
        }

        @Test
        void rejectionsListIsReadOnly() {
            Expense e = inStatus(ExpenseStatus.REJECTED);
            assertThatThrownBy(() -> e.rejections().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class DraftOnlyOperations {

        static final Set<ExpenseStatus> NON_DRAFT = EnumSet.complementOf(EnumSet.of(ExpenseStatus.DRAFT));

        @Test
        void claimantMayEditDraft() {
            Expense e = draft();
            e.edit(CLAIMANT, Money.of("20", "EUR"), Category.TRAVEL, TODAY.minusDays(1), "Bahn", "Ticket", CLOCK);
            assertThat(e.money()).isEqualTo(Money.of("20.00", "EUR"));
            assertThat(e.category()).isEqualTo(Category.TRAVEL);
            assertThat(e.merchant()).isEqualTo("Bahn");
            assertThat(e.note()).isEqualTo("Ticket");
        }

        @Test
        void editValidatesLikeCreation() {
            assertThatThrownBy(() -> draft().edit(
                                    CLAIMANT,
                                    Money.of("20", "EUR"),
                                    Category.TRAVEL,
                                    TODAY.plusDays(1),
                                    "Bahn",
                                    null,
                                    CLOCK))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void editByNonClaimantIsRefused() {
            assertThatThrownBy(() ->
                            draft().edit(OWNER, Money.of("20", "EUR"), Category.TRAVEL, TODAY, "Bahn", null, CLOCK))
                    .isInstanceOf(NotAllowed.class);
        }

        @ParameterizedTest
        @EnumSource(
                value = ExpenseStatus.class,
                names = {"SUBMITTED", "APPROVED", "REJECTED", "REIMBURSED"})
        void editOutsideDraftIsRefused(ExpenseStatus status) {
            assertThatThrownBy(() -> inStatus(status)
                            .edit(CLAIMANT, Money.of("20", "EUR"), Category.TRAVEL, TODAY, "Bahn", null, CLOCK))
                    .isInstanceOf(RuleViolated.class);
        }

        @Test
        void receiptsAndDeleteOnlyForClaimantOfDraft() {
            Expense d = draft();
            assertThat(d.canAddOrRemoveReceipts(CLAIMANT)).isTrue();
            assertThat(d.canDelete(CLAIMANT)).isTrue();
            assertThat(d.canAddOrRemoveReceipts(OWNER)).isFalse();
            assertThat(d.canDelete(OWNER)).isFalse();
            for (ExpenseStatus s : NON_DRAFT) {
                Expense e = inStatus(s);
                assertThat(e.canAddOrRemoveReceipts(CLAIMANT))
                        .as("receipts in %s", s)
                        .isFalse();
                assertThat(e.canDelete(CLAIMANT)).as("delete in %s", s).isFalse();
            }
        }
    }

    @Nested
    class Visibility {

        @Test
        void claimantSeesOwnExpense() {
            assertThat(draft().isVisibleTo(CLAIMANT)).isTrue();
        }

        @Test
        void otherMemberDoesNot() {
            assertThat(draft().isVisibleTo(OTHER_MEMBER)).isFalse();
        }

        @Test
        void approverAndOwnerSeeEverything() {
            assertThat(draft().isVisibleTo(APPROVER)).isTrue();
            assertThat(draft().isVisibleTo(OWNER)).isTrue();
        }

        @Test
        void claimantWhoIsNoLongerOnTheTeamStillSeesOwn() {
            // The domain only knows the actor's role; a removed claimant is handled by the caller.
            assertThat(draft().isVisibleTo(new Actor(CLAIMANT_ID, Role.MEMBER))).isTrue();
        }
    }
}
