package com.ledgerly.expenses.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.internal.application.CreateExpense;
import com.ledgerly.expenses.internal.application.GetExpense;
import com.ledgerly.expenses.internal.domain.*;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import com.ledgerly.shared.role.Role;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ExpenseRepositoryTest {

    @Autowired
    ExpenseRepository repository;

    @Autowired
    CreateExpense createExpense;

    @Autowired
    GetExpense getExpense;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    final UserId claimant = UserId.random();

    Expense newDraft(TeamId team) {
        return Expense.draft(
                team, claimant, Money.of("12.5", "EUR"), Category.MEALS, LocalDate.now(clock), "Cafe", "Lunch", clock);
    }

    @Test
    void roundTripKeepsEveryField() {
        TeamId team = TeamId.random();
        Expense e = newDraft(team);
        e.submit(new Actor(claimant, Role.MEMBER), new ReceiptSummary(1, 0));
        e.reject(new Actor(UserId.random(), Role.APPROVER), "first");
        e.reopen(new Actor(claimant, Role.MEMBER));
        e.submit(new Actor(claimant, Role.MEMBER), new ReceiptSummary(1, 0));
        e.reject(new Actor(UserId.random(), Role.OWNER), "second");
        tx.executeWithoutResult(s -> repository.save(e));

        tx.executeWithoutResult(s -> {
            Expense loaded = repository.findByTeamIdAndId(team, e.id()).orElseThrow();
            assertThat(loaded.money()).isEqualTo(Money.of("12.50", "EUR"));
            assertThat(loaded.category()).isEqualTo(Category.MEALS);
            assertThat(loaded.merchant()).isEqualTo("Cafe");
            assertThat(loaded.note()).isEqualTo("Lunch");
            assertThat(loaded.status()).isEqualTo(ExpenseStatus.REJECTED);
            assertThat(loaded.claimantId()).isEqualTo(claimant);
            assertThat(loaded.rejections()).extracting(Rejection::reason).containsExactly("first", "second");
        });
    }

    @Test
    void useCasesCreateAndRead() {
        TeamId team = TeamId.random();
        var view = createExpense.execute(new CreateExpense.Command(
                team, claimant, Money.of("1500", "JPY"), Category.TRAVEL, LocalDate.now(clock), "JR", null));
        var read = getExpense.execute(team, view.id()).orElseThrow();
        assertThat(read.money()).isEqualTo(Money.of("1500", "JPY"));
        assertThat(read.note()).isNull();
    }

    @Test
    void expenseOfTeamAIsNotFoundWithTeamBsId() {
        TeamId a = TeamId.random();
        Expense e = tx.execute(s -> repository.save(newDraft(a)));
        assertThat(repository.findByTeamIdAndId(TeamId.random(), e.id())).isEmpty();
        assertThat(repository.findByTeamIdAndId(a, e.id())).isPresent();
        assertThat(getExpense.execute(TeamId.random(), e.id())).isEmpty();
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(repository.findByTeamIdAndId(TeamId.random(), ExpenseId.random()))
                .isEmpty();
    }

    @Test
    void secondConcurrentSaveFailsWithOptimisticLock() {
        TeamId team = TeamId.random();
        Expense saved = tx.execute(s -> repository.save(newDraft(team)));
        // Two copies loaded outside any shared session, as two requests would.
        Expense copy1 =
                tx.execute(s -> repository.findByTeamIdAndId(team, saved.id()).orElseThrow());
        Expense copy2 =
                tx.execute(s -> repository.findByTeamIdAndId(team, saved.id()).orElseThrow());
        Actor actor = new Actor(claimant, Role.MEMBER);
        LocalDate day = LocalDate.now(clock);
        copy1.edit(actor, Money.of("1", "EUR"), Category.OTHER, day, "One", null, clock);
        copy2.edit(actor, Money.of("2", "EUR"), Category.OTHER, day, "Two", null, clock);

        tx.executeWithoutResult(s -> repository.save(copy1));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.save(copy2)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
