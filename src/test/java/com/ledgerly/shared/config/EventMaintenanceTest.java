package com.ledgerly.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;

/** The two rules of the event clean-up: how old a stuck event must be before it is redelivered, and how long history stays. */
class EventMaintenanceTest {

    record Call(String method, Object argument) {}

    final List<Call> calls = new ArrayList<>();

    @SuppressWarnings("unchecked")
    <T> T recorder(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            calls.add(new Call(method.getName(), args == null ? null : args[0]));
            return null;
        });
    }

    EventMaintenance maintenance() {
        return new EventMaintenance(
                recorder(IncompleteEventPublications.class), recorder(CompletedEventPublications.class));
    }

    @Test
    void anEventIsOnlyRedeliveredOnceItHasBeenStuckForAtLeastTwoMinutes() {
        maintenance().resubmitStuckEvents();

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().method()).isEqualTo("resubmitIncompletePublicationsOlderThan");
        // Younger events may belong to a listener that is still working (an import in progress): never touch those.
        assertThat((Duration) calls.getFirst().argument()).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void deliveredEventsAreKeptForAWeekAndNeverResubmitted() {
        maintenance().deleteOldCompletedEvents();

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().method()).isEqualTo("deletePublicationsOlderThan");
        assertThat((Duration) calls.getFirst().argument()).isEqualTo(Duration.ofDays(7));
    }
}
