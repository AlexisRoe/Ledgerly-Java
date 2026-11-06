package com.ledgerly.identity.internal.application;

import com.ledgerly.identity.events.UserRegistered;
import com.ledgerly.identity.events.UserVerified;
import com.ledgerly.identity.internal.domain.User;
import com.ledgerly.identity.internal.infrastructure.UserRepository;
import com.ledgerly.shared.id.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Just-in-time provisioning for the {@code keycloak} profile: the first time a person's token arrives, their user row
 * is created. Ledgerly never uses Keycloak's id anywhere else; it uses the id of this row (ADR 0001), so everything
 * outside identity works exactly as before.
 *
 * <p>How a token's person is matched, in order:
 *
 * <ol>
 *   <li>by the provider's {@code sub} (the normal case, after the first time);
 *   <li>by email, but only when the provider says the email is verified: an existing local account with that
 *       address is linked to the {@code sub}. A provider that hands out unverified emails would otherwise let
 *       anyone take over an account by registering its address;
 *   <li>otherwise a new user is created.
 * </ol>
 */
@Service
@Profile("keycloak")
public class ExternalIdentityProvisioner {

    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    ExternalIdentityProvisioner(
            UserRepository users, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.users = users;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @return Ledgerly's id for the person behind a Keycloak token
     * @throws InvalidBearerTokenException when the token cannot be matched or provisioned (no email, or the email
     *     belongs to a local account and the provider has not verified it)
     */
    public UserId resolve(String subject, @Nullable String email, boolean emailVerified) {
        try {
            return inTransaction(subject, email, emailVerified);
        } catch (DataIntegrityViolationException e) {
            // Two first requests of the same person at once: one of them created the row. Look again.
            return inTransaction(subject, email, emailVerified);
        }
    }

    private UserId inTransaction(String subject, @Nullable String email, boolean emailVerified) {
        UserId id = tx.execute(status -> provision(subject, email, emailVerified));
        if (id == null) {
            throw new IllegalStateException("provisioning returned nothing");
        }
        return id;
    }

    private UserId provision(String subject, @Nullable String email, boolean emailVerified) {
        Instant now = clock.instant();
        Optional<User> bySubject = users.findByExternalSubject(subject);
        if (bySubject.isPresent()) {
            User user = bySubject.get();
            // The provider is the authority on verification in this mode; it only ever moves towards "verified".
            if (emailVerified && !user.verified()) {
                user.markVerified();
                events.publishEvent(new UserVerified(user.id(), now));
            }
            return user.id();
        }
        if (email == null || email.isBlank()) {
            throw new InvalidBearerTokenException("The token has no email claim");
        }
        Optional<User> byEmail = users.findByEmail(User.normalise(email));
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            if (!emailVerified || existing.externalSubject() != null) {
                // Not provably the same person, or that account already belongs to a different provider identity.
                throw new InvalidBearerTokenException("The email address belongs to another account");
            }
            existing.linkExternal(subject);
            if (!existing.verified()) {
                existing.markVerified();
                events.publishEvent(new UserVerified(existing.id(), now));
            }
            return existing.id();
        }
        User created = users.saveAndFlush(User.external(subject, email, emailVerified, now));
        events.publishEvent(new UserRegistered(created.id(), now));
        if (emailVerified) {
            events.publishEvent(new UserVerified(created.id(), now));
        }
        return created.id();
    }
}
