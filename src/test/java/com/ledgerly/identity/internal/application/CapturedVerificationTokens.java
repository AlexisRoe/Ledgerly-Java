package com.ledgerly.identity.internal.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Test stand-in for the mailbox: remembers the verification token issued for each user. */
@Component
@Profile("test")
public class CapturedVerificationTokens {

    private final Map<UUID, String> tokens = new ConcurrentHashMap<>();

    @EventListener
    void on(VerificationLinkIssued event) {
        tokens.put(event.userId(), event.token());
    }

    public String forUser(UUID userId) {
        return tokens.get(userId);
    }
}
