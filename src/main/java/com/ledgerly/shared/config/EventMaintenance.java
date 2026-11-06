package com.ledgerly.shared.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Looks after the event publication registry while the application runs. At start-up Spring Modulith already
 * re-delivers what was left over (see {@code spring.modulith.events.republish-outstanding-events-on-restart});
 * this covers a listener that failed without the application stopping, such as a malware scanner that was down for
 * a few minutes.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledgerly.events.resubmit-enabled", havingValue = "true", matchIfMissing = true)
class EventMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EventMaintenance.class);

    /** Long enough that an event whose listener is still busy (an import in progress) is not started twice. */
    private static final Duration MINIMUM_AGE = Duration.ofMinutes(2);

    private static final Duration KEEP_COMPLETED = Duration.ofDays(7);

    private final IncompleteEventPublications incomplete;
    private final CompletedEventPublications completed;

    /**
     * @param incomplete events whose listener has not finished
     * @param completed events already delivered
     */
    EventMaintenance(IncompleteEventPublications incomplete, CompletedEventPublications completed) {
        this.incomplete = incomplete;
        this.completed = completed;
    }

    /** Every minute: delivers again the events whose listener never finished (older than two minutes). */
    @Scheduled(fixedDelayString = "${ledgerly.events.resubmit-interval:PT1M}", initialDelayString = "PT1M")
    void resubmitStuckEvents() {
        incomplete.resubmitIncompletePublicationsOlderThan(MINIMUM_AGE);
    }

    /** Nightly: removes delivered events older than seven days from the publication registry. */
    @Scheduled(cron = "0 17 3 * * *")
    void deleteOldCompletedEvents() {
        completed.deletePublicationsOlderThan(KEEP_COMPLETED);
        log.info("Deleted completed event publications older than {}", KEEP_COMPLETED);
    }
}
