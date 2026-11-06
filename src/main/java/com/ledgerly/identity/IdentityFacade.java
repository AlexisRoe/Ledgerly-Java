package com.ledgerly.identity;

import com.ledgerly.shared.id.UserId;
import java.util.Optional;

/** The identity module's public API for other modules. */
public interface IdentityFacade {

    /** The account's id, email and verified flag, or empty when it does not exist. */
    Optional<UserSummary> find(UserId id);

    /** True only for an existing account whose email is verified. */
    default boolean isVerified(UserId id) {
        return find(id).map(UserSummary::verified).orElse(false);
    }
}
