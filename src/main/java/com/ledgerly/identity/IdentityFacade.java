package com.ledgerly.identity;

import com.ledgerly.shared.id.UserId;
import java.util.Optional;

/** The identity module's public API for other modules. */
public interface IdentityFacade {

    Optional<UserSummary> find(UserId id);

    default boolean isVerified(UserId id) {
        return find(id).map(UserSummary::verified).orElse(false);
    }
}
