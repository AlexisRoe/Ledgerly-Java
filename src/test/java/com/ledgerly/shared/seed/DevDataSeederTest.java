package com.ledgerly.shared.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestcontainersConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** The demo data a developer logs in with: complete, repeatable, and usable with the real password check. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DevDataSeederTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectProvider<PasswordEncoder> encoders;

    @AfterEach
    void removeDemoData() {
        jdbc.sql("delete from expense_rejections where expense_id in (select id from expenses where team_id = :t)")
                .param("t", DevDataSeeder.TEAM)
                .update();
        jdbc.sql("delete from expenses where team_id = :t")
                .param("t", DevDataSeeder.TEAM)
                .update();
        jdbc.sql("delete from invitations where team_id = :t")
                .param("t", DevDataSeeder.TEAM)
                .update();
        jdbc.sql("delete from memberships where team_id = :t")
                .param("t", DevDataSeeder.TEAM)
                .update();
        jdbc.sql("delete from teams where id = :t")
                .param("t", DevDataSeeder.TEAM)
                .update();
        jdbc.sql("delete from users where email in ('alice@example.com','bob@example.com','carol@example.com',"
                        + "'dave@example.com')")
                .update();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table + " where team_id = :t")
                .param("t", DevDataSeeder.TEAM)
                .query(Long.class)
                .single();
    }

    @Test
    void createsATeamWithOneUserPerRoleAndExpensesInEveryStatus() {
        new DevDataSeeder(jdbc, encoders, Clock.systemUTC()).run(new DefaultApplicationArguments());

        var roles = jdbc.sql("select role from memberships where team_id = :t order by role")
                .param("t", DevDataSeeder.TEAM)
                .query(String.class)
                .list();
        assertThat(roles).containsExactlyInAnyOrder("OWNER", "APPROVER", "MEMBER");
        var statuses = jdbc.sql("select distinct status from expenses where team_id = :t")
                .param("t", DevDataSeeder.TEAM)
                .query(String.class)
                .list();
        assertThat(statuses).containsExactlyInAnyOrder("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "REIMBURSED");
        assertThat(count("invitations")).isEqualTo(1);
    }

    @Test
    void theDemoPasswordUnlocksTheSeededAccountsAndOnlyThose() {
        new DevDataSeeder(jdbc, encoders, Clock.systemUTC()).run(new DefaultApplicationArguments());

        String hash = jdbc.sql("select password_hash from users where email = 'alice@example.com'")
                .query(String.class)
                .single();
        assertThat(passwordEncoder.matches(DevDataSeeder.PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("something-else", hash)).isFalse();
        assertThat(hash).doesNotContain(DevDataSeeder.PASSWORD);
        boolean daveVerified = jdbc.sql("select verified from users where email = 'dave@example.com'")
                .query(Boolean.class)
                .single();
        assertThat(daveVerified)
                .as("one account stays unverified, to try the verification rules")
                .isFalse();
    }

    @Test
    void runningTwiceAddsNothing() {
        var seeder = new DevDataSeeder(jdbc, encoders, Clock.systemUTC());
        seeder.run(new DefaultApplicationArguments());
        long expenses = count("expenses");
        long members = count("memberships");

        seeder.run(new DefaultApplicationArguments());

        assertThat(count("expenses")).isEqualTo(expenses);
        assertThat(count("memberships")).isEqualTo(members);
    }
}
