package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.IdentityFacade;
import com.ledgerly.identity.UserSummary;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import com.ledgerly.shared.id.UserId;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers other modules' account questions from the users table. */
@Service
class IdentityFacadeImpl implements IdentityFacade {

    private final UserRepository users;

    IdentityFacadeImpl(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> find(UserId id) {
        return users.findById(id.value()).map(u -> new UserSummary(u.id(), u.email(), u.verified()));
    }
}
