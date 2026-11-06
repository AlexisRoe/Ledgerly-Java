package com.ledgerly.identity.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Stands in for an email service: in the local profile only, prints the verification token to the console. */
@Component
@Profile("local & !test")
class LocalVerificationLogger {

    private static final Logger log = LoggerFactory.getLogger(LocalVerificationLogger.class);

    @EventListener
    void on(VerificationLinkIssued event) {
        log.info(
                "[local only] verify user {} with POST /api/v1/auth/verify {\"token\":\"{}\"}",
                event.userId(),
                event.token());
    }
}
