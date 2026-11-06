package com.ledgerly.identity.internal.application;

import java.time.Duration;

/** The result of logging in or refreshing: an access token for the body, a refresh token for the cookie. */
public record Session(String accessToken, long expiresInSeconds, String refreshToken, Duration refreshTtl) {

    @Override
    public String toString() {
        return "Session[***]";
    }
}
