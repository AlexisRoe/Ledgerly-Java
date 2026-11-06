package com.ledgerly.teams.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TeamSeeder;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.Forbidden;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two owners act on each other at the same moment. Without the team-row lock both see "two owners left" and both
 * succeed, leaving none. With it they run one after the other and the second finds it is no longer allowed.
 */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class LastOwnerConcurrencyTest {

    @Autowired
    ChangeMemberRole changeRole;

    @Autowired
    RemoveMember removeMember;

    @Autowired
    LeaveTeam leaveTeam;

    @Autowired
    JdbcClient jdbc;

    final UUID team = UUID.randomUUID();
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    void twoOwners() {
        TeamSeeder seeder = new TeamSeeder(jdbc);
        seeder.member(team, a, "OWNER");
        seeder.member(team, b, "OWNER");
    }

    int owners() {
        return jdbc.sql("select count(*) from memberships where team_id = :t and role = 'OWNER'")
                .param("t", team)
                .query(Integer.class)
                .single();
    }

    /** Runs both actions at the same instant; returns the exception each one threw (null = it succeeded). */
    List<RuntimeException> race(Runnable first, Runnable second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<RuntimeException> one = () -> attempt(start, first);
            Callable<RuntimeException> two = () -> attempt(start, second);
            Future<RuntimeException> f1 = pool.submit(one);
            Future<RuntimeException> f2 = pool.submit(two);
            return java.util.Arrays.asList(f1.get(), f2.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static RuntimeException attempt(CyclicBarrier start, Runnable action) throws Exception {
        start.await();
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static long successes(List<RuntimeException> outcomes) {
        return outcomes.stream().filter(e -> e == null).count();
    }

    @RepeatedTest(10)
    void twoOwnersDemotingEachOtherLeaveExactlyOneOwner() throws Exception {
        twoOwners();
        var outcomes = race(
                () -> changeRole.execute(team, new UserId(a), b, Role.MEMBER),
                () -> changeRole.execute(team, new UserId(b), a, Role.MEMBER));
        assertThat(owners()).isEqualTo(1);
        assertThat(successes(outcomes)).isEqualTo(1);
        // The loser was an owner when it started but no longer is by the time it holds the lock.
        assertThat(outcomes).filteredOn(e -> e != null).allMatch(e -> e instanceof Forbidden || e instanceof Conflict);
    }

    @RepeatedTest(10)
    void twoOwnersRemovingEachOtherLeaveExactlyOneOwner() throws Exception {
        twoOwners();
        var outcomes = race(
                () -> removeMember.execute(team, new UserId(a), b), () -> removeMember.execute(team, new UserId(b), a));
        assertThat(owners()).isEqualTo(1);
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(outcomes)
                .filteredOn(e -> e != null)
                .allMatch(e -> e instanceof Forbidden || e instanceof NotFound || e instanceof Conflict);
    }

    @RepeatedTest(10)
    void twoOwnersLeavingAtTheSameTimeLeaveExactlyOneOwner() throws Exception {
        twoOwners();
        var outcomes = race(() -> leaveTeam.execute(team, new UserId(a)), () -> leaveTeam.execute(team, new UserId(b)));
        assertThat(owners()).isEqualTo(1);
        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(outcomes).filteredOn(e -> e != null).allMatch(e -> e instanceof Conflict);
    }
}
