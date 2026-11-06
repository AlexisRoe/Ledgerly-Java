package com.ledgerly.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * Tests the audit module on its own: only audit and the shared kernel are started, so the test publishes the expenses
 * module's events itself, the way that module would, and uses the {@link Scenario} API to wait for the effect.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@Import(TestcontainersConfiguration.class)
class AuditModuleTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aSubmittedExpenseEventLeavesAnAuditRow(Scenario scenario) {
        var event = new ExpenseEvent.Submitted(TeamId.random(), ExpenseId.random(), UserId.random(), Instant.now());

        scenario.publish(event)
                .andWaitForStateChange(() -> rowsFor(event.eventId()))
                .andVerify(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst()).isEqualTo("EXPENSE_SUBMITTED");
                });
    }

    @Test
    void theSameEventPublishedTwiceLeavesOneRow(Scenario scenario) {
        var event = new ExpenseEvent.Approved(TeamId.random(), ExpenseId.random(), UserId.random(), Instant.now());

        scenario.publish(event)
                .andWaitForStateChange(() -> rowsFor(event.eventId()))
                .andVerify(rows -> {});
        scenario.publish(event)
                .andWaitForStateChange(() -> rowsFor(event.eventId()), rows -> rows.size() >= 1)
                .andVerify(rows -> assertThat(rows).hasSize(1));
    }

    private List<String> rowsFor(UUID eventId) {
        List<String> types = jdbc.sql("select event_type from audit_events where event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .list();
        return types.isEmpty() ? null : types;
    }
}
