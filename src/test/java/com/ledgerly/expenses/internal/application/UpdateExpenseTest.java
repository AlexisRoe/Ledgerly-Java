package com.ledgerly.expenses.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.expenses.internal.domain.NotAllowed;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.error.PreconditionFailed;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Partial edits: only what was sent changes, only the names of changed fields are announced, refusals leave no trace. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class UpdateExpenseTest {

    @Autowired
    UpdateExpense update;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    UUID team;
    UUID claimant;
    UUID expense;

    @BeforeEach
    void draftOfTheClaimant() {
        team = UUID.randomUUID();
        claimant = UUID.randomUUID();
        new TeamSeeder(jdbc).member(team, claimant, "MEMBER");
        expense = new ExpenseSeeder(jdbc)
                .insert(
                        team,
                        claimant,
                        "10.00",
                        "EUR",
                        "TRAVEL",
                        "DRAFT",
                        LocalDate.now().minusDays(1),
                        Instant.now());
    }

    UpdateExpense.Command edit(UUID caller, long version, Money money, String merchant, String note) {
        return new UpdateExpense.Command(
                new TeamId(team),
                new ExpenseId(expense),
                new UserId(caller),
                version,
                money,
                null,
                null,
                merchant,
                note);
    }

    ExpenseEvent.Edited theEditEvent() {
        return events.stream(ExpenseEvent.Edited.class).findFirst().orElseThrow();
    }

    String column(String name) {
        return jdbc.sql("select " + name + " from expenses where id = :id")
                .param("id", expense)
                .query(String.class)
                .single();
    }

    @Test
    void anEditThatSendsNothingChangesNothingAndAnnouncesNoFields() {
        var view = update.execute(edit(claimant, 0, null, null, null));

        assertThat(view.merchant()).isEqualTo("Seed");
        assertThat(view.money()).isEqualTo(Money.of("10.00", "EUR"));
        assertThat(theEditEvent().changedFields()).isEmpty();
    }

    @Test
    void onlyTheFieldsThatReallyChangeAreNamed() {
        // merchant is sent but equals the stored value, so it is not a change
        var view = update.execute(edit(claimant, 0, Money.of("12.00", "EUR"), "Seed", null));

        assertThat(view.money()).isEqualTo(Money.of("12.00", "EUR"));
        assertThat(theEditEvent().changedFields()).containsExactly("money");
    }

    @Test
    void theAnnouncementCarriesFieldNamesNeverValues() {
        update.execute(edit(claimant, 0, null, "Secret Supplier Ltd", "private note"));

        var event = theEditEvent();
        assertThat(event.changedFields()).containsExactlyInAnyOrder("merchant", "note");
        assertThat(event.toString()).doesNotContain("Secret Supplier").doesNotContain("private note");
    }

    @Test
    void aBlankNoteClearsTheNoteAndSendingTheSameNoteAgainIsNoChange() {
        var withNote = update.execute(edit(claimant, 0, null, null, "keep me"));
        assertThat(withNote.note()).isEqualTo("keep me");

        var same = update.execute(edit(claimant, withNote.version(), null, null, "keep me"));
        assertThat(events.stream(ExpenseEvent.Edited.class)
                        .reduce((a, b) -> b)
                        .orElseThrow()
                        .changedFields())
                .as("same note again")
                .isEmpty();

        var cleared = update.execute(edit(claimant, same.version(), null, null, "   "));
        assertThat(cleared.note()).isNull();
        assertThat(events.stream(ExpenseEvent.Edited.class)
                        .reduce((a, b) -> b)
                        .orElseThrow()
                        .changedFields())
                .containsExactly("note");
    }

    @Test
    void aStaleVersionIsRefusedAndChangesAndAnnouncesNothing() {
        assertThatThrownBy(() -> update.execute(edit(claimant, 99, null, "Hijacked", null)))
                .isInstanceOf(PreconditionFailed.class);

        assertThat(column("merchant")).isEqualTo("Seed");
        assertThat(events.stream(ExpenseEvent.Edited.class)).isEmpty();
    }

    @Test
    void aTeammateWhoIsNotTheClaimantCannotEditEvenWithAHigherRole() {
        UUID owner = UUID.randomUUID();
        new TeamSeeder(jdbc).member(team, owner, "OWNER");

        assertThatThrownBy(() -> update.execute(edit(owner, 0, null, "Hijacked", null)))
                .isInstanceOf(NotAllowed.class);

        assertThat(column("merchant")).isEqualTo("Seed");
        assertThat(events.stream(ExpenseEvent.Edited.class)).isEmpty();
    }

    @Test
    void someoneOutsideTheTeamLearnsNothingNotEvenThatTheExpenseExists() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> update.execute(edit(stranger, 0, null, "Hijacked", null)))
                .isInstanceOf(NotFound.class);

        assertThat(column("merchant")).isEqualTo("Seed");
    }

    @Test
    void anEditCannotMoveTheExpenseIntoTheFuture() {
        var future = new UpdateExpense.Command(
                new TeamId(team),
                new ExpenseId(expense),
                new UserId(claimant),
                0,
                null,
                Category.MEALS,
                LocalDate.now().plusDays(3),
                null,
                null);

        assertThatThrownBy(() -> update.execute(future))
                .isInstanceOf(com.ledgerly.expenses.internal.domain.InvalidInput.class);

        assertThat(column("category")).isEqualTo("TRAVEL");
        assertThat(events.stream(ExpenseEvent.Edited.class)).isEmpty();
    }
}
