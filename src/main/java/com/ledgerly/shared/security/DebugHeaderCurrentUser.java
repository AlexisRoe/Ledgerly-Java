package com.ledgerly.shared.security;

import com.ledgerly.shared.error.Unauthenticated;
import com.ledgerly.shared.id.UserId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * TEMPORARY (until M5): trusts the {@code X-Debug-User-Id} header. Active only in the {@code local} and
 * {@code test} profiles; a test asserts the {@code prod} profile has no such bean.
 */
@Component
@Profile({"local", "test"})
public class DebugHeaderCurrentUser implements CurrentUser {

    public static final String USER_HEADER = "X-Debug-User-Id";

    @Override
    public UserId id() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new Unauthenticated();
        }
        HttpServletRequest request = attributes.getRequest();
        String header = request.getHeader(USER_HEADER);
        if (header == null) {
            throw new Unauthenticated();
        }
        try {
            return new UserId(UUID.fromString(header));
        } catch (IllegalArgumentException e) {
            throw new Unauthenticated();
        }
    }
}
