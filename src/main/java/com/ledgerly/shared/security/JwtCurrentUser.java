package com.ledgerly.shared.security;

import com.ledgerly.shared.error.Unauthenticated;
import com.ledgerly.shared.id.UserId;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The caller is whoever the validated access token's {@code sub} claim names. */
@Component
public class JwtCurrentUser implements CurrentUser {

    @Override
    public UserId id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new Unauthenticated();
        }
        try {
            return new UserId(UUID.fromString(token.getToken().getSubject()));
        } catch (IllegalArgumentException e) {
            throw new Unauthenticated();
        }
    }
}
