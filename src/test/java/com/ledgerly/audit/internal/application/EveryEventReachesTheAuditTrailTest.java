package com.ledgerly.audit.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.identity.events.IdentityEvent;
import com.ledgerly.identity.events.RefreshTokenReuseDetected;
import com.ledgerly.identity.events.UserRegistered;
import com.ledgerly.identity.events.UserVerified;
import com.ledgerly.imports.events.ImportCompleted;
import com.ledgerly.receipts.events.ReceiptEvent;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.teams.events.TeamEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adding an event type without an audit listener must fail a test. The samples below are checked against the
 * permitted subclasses of every sealed event interface, then each one is published and looked up in the trail.
 */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class EveryEventReachesTheAuditTrailTest {

    record Sample(Object event, String expectedType, boolean hasTeam, boolean hasActor) {}

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    final TeamId team = TeamId.random();
    final UserId actor = UserId.random();
    final ExpenseId expense = ExpenseId.random();
    final UUID other = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-05T10:00:00Z");

    Map<Class<?>, Sample> samples() {
        Map<Class<?>, Sample> samples = new LinkedHashMap<>();
        // identity: no team
        add(samples, new UserRegistered(actor, now), "USER_REGISTERED", false, true);
        add(samples, new UserVerified(actor, now), "USER_VERIFIED", false, true);
        add(samples, new RefreshTokenReuseDetected(actor, other, now), "REFRESH_TOKEN_REUSE_DETECTED", false, true);
        // teams
        add(samples, new TeamEvent.TeamCreated(team, actor, now), "TEAM_CREATED", true, true);
        add(samples, new TeamEvent.MemberInvited(team, actor, other, "APPROVER", now), "MEMBER_INVITED", true, true);
        add(samples, new TeamEvent.InvitationRevoked(team, actor, other, now), "INVITATION_REVOKED", true, true);
        add(samples, new TeamEvent.InvitationAccepted(team, actor, other, now), "INVITATION_ACCEPTED", true, true);
        add(samples, new TeamEvent.InvitationDeclined(team, actor, other, now), "INVITATION_DECLINED", true, true);
        add(
                samples,
                new TeamEvent.MemberRoleChanged(team, actor, UserId.random(), "MEMBER", "OWNER", now),
                "MEMBER_ROLE_CHANGED",
                true,
                true);
        add(samples, new TeamEvent.MemberRemoved(team, actor, UserId.random(), now), "MEMBER_REMOVED", true, true);
        add(samples, new TeamEvent.MemberLeft(team, actor, now), "MEMBER_LEFT", true, true);
        // expenses
        add(samples, new ExpenseEvent.Created(team, expense, actor, now), "EXPENSE_CREATED", true, true);
        add(
                samples,
                new ExpenseEvent.Edited(team, expense, actor, List.of("merchant"), now),
                "EXPENSE_EDITED",
                true,
                true);
        add(samples, new ExpenseEvent.Deleted(team, expense, actor, now), "EXPENSE_DELETED", true, true);
        add(samples, new ExpenseEvent.Submitted(team, expense, actor, now), "EXPENSE_SUBMITTED", true, true);
        add(samples, new ExpenseEvent.Withdrawn(team, expense, actor, now), "EXPENSE_WITHDRAWN", true, true);
        add(samples, new ExpenseEvent.Approved(team, expense, actor, now), "EXPENSE_APPROVED", true, true);
        add(samples, new ExpenseEvent.Rejected(team, expense, actor, now), "EXPENSE_REJECTED", true, true);
        add(samples, new ExpenseEvent.Reopened(team, expense, actor, now), "EXPENSE_REOPENED", true, true);
        add(samples, new ExpenseEvent.Reimbursed(team, expense, actor, now), "EXPENSE_REIMBURSED", true, true);
        // receipts (the scan results are done by the system: no actor)
        add(samples, new ReceiptEvent.Uploaded(team, expense, other, actor, now), "RECEIPT_UPLOADED", true, true);
        add(samples, new ReceiptEvent.Accepted(team, expense, other, now), "RECEIPT_ACCEPTED", true, false);
        add(samples, new ReceiptEvent.Quarantined(team, expense, other, now), "RECEIPT_QUARANTINED", true, false);
        add(samples, new ReceiptEvent.Deleted(team, expense, other, actor, now), "RECEIPT_DELETED", true, true);
        // imports
        add(samples, new ImportCompleted(team, actor, other, 10, 2, false, now), "IMPORT_COMPLETED", true, true);
        return samples;
    }

    private static void add(
            Map<Class<?>, Sample> samples, Object event, String type, boolean hasTeam, boolean hasActor) {
        samples.put(event.getClass(), new Sample(event, type, hasTeam, hasActor));
    }

    static UUID eventIdOf(Object event) {
        try {
            return (UUID) event.getClass().getMethod("eventId").invoke(event);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyEventTypeInTheCodebaseHasASampleHere() {
        List<Class<?>> all = new ArrayList<>();
        for (Class<?> sealed : List.of(IdentityEvent.class, TeamEvent.class, ExpenseEvent.class, ReceiptEvent.class)) {
            all.addAll(List.of(sealed.getPermittedSubclasses()));
        }
        all.add(ImportCompleted.class);
        assertThat(samples().keySet()).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void everySampleReachesTheTrailWithTheRightShape() {
        var all = samples();
        // Publish everything inside one transaction each, as the real code does.
        all.values().forEach(s -> tx.executeWithoutResult(status -> publisher.publishEvent(s.event())));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (Sample s : all.values()) {
                var row = jdbc.sql("select event_type, team_id, actor_id from audit_events where event_id = :id")
                        .param("id", eventIdOf(s.event()))
                        .query((rs, n) -> new Object[] {
                            rs.getString("event_type"), rs.getObject("team_id"), rs.getObject("actor_id")
                        })
                        .optional();
                assertThat(row).as(s.expectedType()).isPresent();
                assertThat(row.get()[0]).isEqualTo(s.expectedType());
                assertThat(row.get()[1] != null)
                        .as(s.expectedType() + " has a team")
                        .isEqualTo(s.hasTeam());
                assertThat(row.get()[2] != null)
                        .as(s.expectedType() + " has an actor")
                        .isEqualTo(s.hasActor());
            }
        });
    }

    @Test
    void anEventDeliveredTwiceIsRecordedOnce() {
        var event = new ExpenseEvent.Submitted(team, expense, actor, now);
        tx.executeWithoutResult(status -> publisher.publishEvent(event));
        tx.executeWithoutResult(status -> publisher.publishEvent(event));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(count(event.eventId())).isGreaterThanOrEqualTo(1));
        // Give the second delivery time to be processed too, then check it did not add a row.
        await().pollDelay(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(count(event.eventId())).isEqualTo(1));
    }

    @Test
    void detailsHoldIdsAndFieldNamesOnly() {
        var edited = new ExpenseEvent.Edited(team, expense, actor, List.of("merchant", "note"), now);
        var invited = new TeamEvent.MemberInvited(team, actor, other, "APPROVER", now);
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(edited);
            publisher.publishEvent(invited);
        });
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(details(edited.eventId())).isEqualTo("{\"changedFields\": [\"merchant\", \"note\"]}");
            assertThat(details(invited.eventId())).isEqualTo("{\"role\": \"APPROVER\"}");
        });
    }

    private int count(UUID eventId) {
        return jdbc.sql("select count(*) from audit_events where event_id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }

    private String details(UUID eventId) {
        return jdbc.sql("select cast(details as text) from audit_events where event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .optional()
                .orElse("");
    }
}
