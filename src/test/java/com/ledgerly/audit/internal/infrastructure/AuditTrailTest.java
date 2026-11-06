package com.ledgerly.audit.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.TestcontainersConfiguration;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class AuditTrailTest {

    @Autowired
    AuditTrail trail;

    @Autowired
    JdbcClient jdbc;

    UUID append(UUID team) {
        UUID eventId = UUID.randomUUID();
        trail.append(new AuditTrail.Entry(
                eventId,
                team,
                UUID.randomUUID(),
                "TEST_EVENT",
                "EXPENSE",
                UUID.randomUUID(),
                "{\"k\":\"v\"}",
                Instant.now()));
        return eventId;
    }

    int rows(UUID eventId) {
        return jdbc.sql("select count(*) from audit_events where event_id = :e")
                .param("e", eventId)
                .query(Integer.class)
                .single();
    }

    // --- append-only: the repository ----------------------------------------------------------------------

    @Test
    void theRepositoryOffersNoWayToChangeOrRemoveARow() {
        List<String> publicMethods = Arrays.stream(AuditTrail.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .toList();
        assertThat(publicMethods).containsExactlyInAnyOrder("append", "page");
    }

    // --- append-only: the database ---------------------------------------------------------------------------

    @Test
    void aRawUpdateFails() {
        UUID eventId = append(UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.sql("update audit_events set event_type = 'FORGED' where event_id = :e")
                        .param("e", eventId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.sql("select event_type from audit_events where event_id = :e")
                        .param("e", eventId)
                        .query(String.class)
                        .single())
                .isEqualTo("TEST_EVENT");
    }

    @Test
    void aRawDeleteFails() {
        UUID eventId = append(UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.sql("delete from audit_events where event_id = :e")
                        .param("e", eventId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(rows(eventId)).isEqualTo(1);
    }

    @Test
    void truncateFailsToo() {
        append(UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.sql("truncate audit_events").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void updateAndDeleteArePrivilegesNobodyHoldsNotEvenTheOwner() {
        // Superusers ignore privileges (the test database user is one), so read the grants themselves.
        List<String> granted = jdbc.sql("""
                        select distinct privilege_type from information_schema.role_table_grants
                        where table_name = 'audit_events'
                        """).query(String.class).list();
        assertThat(granted).doesNotContain("UPDATE", "DELETE", "TRUNCATE");
        assertThat(granted).contains("INSERT", "SELECT");
    }

    // --- idempotent on the event id --------------------------------------------------------------------------

    @Test
    void recordingTheSameEventTwiceAddsOneRow() {
        UUID eventId = UUID.randomUUID();
        var entry =
                new AuditTrail.Entry(eventId, UUID.randomUUID(), null, "TEST_EVENT", null, null, "{}", Instant.now());
        trail.append(entry);
        trail.append(entry);
        trail.append(entry);
        assertThat(rows(eventId)).isEqualTo(1);
    }

    @Test
    void teamlessRowsAreNeverPartOfATeamsPage() {
        UUID team = UUID.randomUUID();
        append(team);
        trail.append(new AuditTrail.Entry(
                UUID.randomUUID(), null, UUID.randomUUID(), "USER_REGISTERED", "USER", null, "{}", Instant.now()));
        assertThat(trail.page(team, null, 50).items()).hasSize(1);
        assertThat(trail.page(team, null, 50).items()).allMatch(r -> team.equals(r.teamId()));
    }
}
