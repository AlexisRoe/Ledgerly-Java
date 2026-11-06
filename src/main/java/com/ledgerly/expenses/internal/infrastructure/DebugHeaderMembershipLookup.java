package com.ledgerly.expenses.internal.infrastructure;

import com.ledgerly.expenses.internal.application.MembershipLookup;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** TEMPORARY (until M6): trusts the {@code X-Debug-Role} header and ignores the team. */
@Component
@Profile({"local", "test"})
class DebugHeaderMembershipLookup implements MembershipLookup {

    static final String ROLE_HEADER = "X-Debug-Role";

    @Override
    public Optional<Role> roleOf(TeamId teamId, UserId userId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String header = attributes.getRequest().getHeader(ROLE_HEADER);
        if (header == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Role.valueOf(header.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
