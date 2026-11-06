package com.ledgerly.shared.seed;

import com.ledgerly.shared.id.UuidV7;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Fills an empty local database with a small team so the API can be tried at once (see {@code docs/deployment.md},
 * "Demo data"). Runs only with the {@code local} profile and never in tests; it does nothing when the demo team
 * already exists, so restarting adds no duplicates. Writes with plain SQL, not through the modules, so it needs
 * nothing from their internals.
 *
 * <p>Every demo user has the password {@value #PASSWORD}. Under the {@code keycloak} profile there is no password
 * encoder, so no users are created (the identity provider owns them).
 */
@Component
@Profile("local & !test")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    /** Shared password of the demo users. Local development only. */
    static final String PASSWORD = "correct-horse-battery";

    /** Fixed so that docs and API collections can refer to it. */
    static final UUID TEAM = UUID.fromString("00000000-0000-7000-8000-00000000a001");

    private final JdbcClient jdbc;
    private final ObjectProvider<PasswordEncoder> encoder;
    private final Clock clock;

    DevDataSeeder(JdbcClient jdbc, ObjectProvider<PasswordEncoder> encoder, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.clock = clock;
    }

    /** Creates users, the team, its memberships, one invitation and expenses in every status; skips if already there. */
    @Override
    public void run(ApplicationArguments args) {
        PasswordEncoder passwordEncoder = encoder.getIfAvailable();
        if (passwordEncoder == null) {
            log.info("Demo data skipped: accounts are managed by the identity provider");
            return;
        }
        if (jdbc.sql("select count(*) from teams where id = :id")
                        .param("id", TEAM)
                        .query(Long.class)
                        .single()
                > 0) {
            return;
        }
        Instant now = clock.instant();
        String hash = passwordEncoder.encode(PASSWORD);

        UUID alice = user("alice@example.com", hash, true, now);
        UUID bob = user("bob@example.com", hash, true, now);
        UUID carol = user("carol@example.com", hash, true, now);
        user("dave@example.com", hash, false, now);

        jdbc.sql("insert into teams (id, name, created_at) values (:id, 'Acme Travel', :now)")
                .param("id", TEAM)
                .param("now", Timestamp.from(now))
                .update();
        member(alice, "OWNER", now);
        member(bob, "APPROVER", now);
        member(carol, "MEMBER", now);

        jdbc.sql("""
                        insert into invitations (id, team_id, email, role, status, invited_by, created_at, expires_at)
                        values (:id, :team, 'dave@example.com', 'MEMBER', 'PENDING', :by, :now, :expires)
                        """)
                .param("id", UuidV7.generate())
                .param("team", TEAM)
                .param("by", alice)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(7, ChronoUnit.DAYS)))
                .update();

        LocalDate today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        expense(carol, "38.40", "EUR", "MEALS", today.minusDays(2), "Cafe Central", "DRAFT", now);
        expense(carol, "212.00", "EUR", "TRAVEL", today.minusDays(6), "Rail Europe", "SUBMITTED", now);
        expense(carol, "49.99", "EUR", "SOFTWARE", today.minusDays(9), "Editor Inc", "APPROVED", now);
        UUID rejected = expense(carol, "980.00", "EUR", "OFFICE", today.minusDays(12), "Desk Depot", "REJECTED", now);
        expense(carol, "75.00", "EUR", "TRAVEL", today.minusDays(20), "City Taxi", "REIMBURSED", now);
        expense(bob, "19.90", "USD", "OTHER", today.minusDays(1), "Parking", "DRAFT", now);
        expense(alice, "120.00", "EUR", "MEALS", today.minusDays(4), "Team dinner", "SUBMITTED", now);
        jdbc.sql("""
                        insert into expense_rejections (expense_id, position, rejected_by, reason)
                        values (:expense, 0, :by, 'Over the office budget; please get a quote first.')
                        """).param("expense", rejected).param("by", bob).update();

        log.info(
                "Demo data created: team 'Acme Travel' with alice (OWNER), bob (APPROVER), carol (MEMBER), "
                        + "dave (unverified, invited). Password for all: {}",
                PASSWORD);
    }

    private UUID user(String email, String hash, boolean verified, Instant now) {
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        insert into users (id, email, password_hash, verified, created_at)
                        values (:id, :email, :hash, :verified, :now)
                        on conflict (email) do nothing
                        """)
                .param("id", id)
                .param("email", email)
                .param("hash", hash)
                .param("verified", verified)
                .param("now", Timestamp.from(now))
                .update();
        return jdbc.sql("select id from users where email = :email")
                .param("email", email)
                .query(UUID.class)
                .single();
    }

    private void member(UUID user, String role, Instant now) {
        jdbc.sql("insert into memberships (team_id, user_id, role, joined_at) values (:team, :user, :role, :now)")
                .param("team", TEAM)
                .param("user", user)
                .param("role", role)
                .param("now", Timestamp.from(now))
                .update();
    }

    private UUID expense(
            UUID claimant,
            String amount,
            String currency,
            String category,
            LocalDate incurredOn,
            String merchant,
            String status,
            Instant now) {
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        insert into expenses (id, team_id, claimant_id, amount, currency, category, incurred_on,
                                              merchant, status, version, created_at, updated_at)
                        values (:id, :team, :claimant, :amount, :currency, :category, :incurredOn,
                                :merchant, :status, 0, :now, :now)
                        """)
                .param("id", id)
                .param("team", TEAM)
                .param("claimant", claimant)
                .param("amount", new BigDecimal(amount))
                .param("currency", currency)
                .param("category", category)
                .param("incurredOn", java.sql.Date.valueOf(incurredOn))
                .param("merchant", merchant)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .update();
        return id;
    }
}
