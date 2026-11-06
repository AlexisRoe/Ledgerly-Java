package com.ledgerly.identity.internal.application;

import java.util.UUID;

/**
 * Internal to identity: asks "the mailer" to deliver a verification token. It carries the secret, so it is
 * never published outside the module and never logged (the local-only listener is the one exception).
 */
public record VerificationLinkIssued(UUID userId, String token) {

    @Override
    public String toString() {
        return "VerificationLinkIssued[userId=" + userId + ", token=***]";
    }
}
