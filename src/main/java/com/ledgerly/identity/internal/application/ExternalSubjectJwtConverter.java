package com.ledgerly.identity.internal.application;

import com.ledgerly.shared.id.UserId;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * The seam that keeps every other module unchanged. A Keycloak token's {@code sub} is Keycloak's id; the rest of
 * Ledgerly expects {@code sub} to be a Ledgerly user id (that is what {@code CurrentUser}, the log context, the rate
 * limiter and team authorization read). So after the token has been verified, its {@code sub} is replaced by the
 * id of the matching user row, which is created on first use.
 */
@Component
@Profile("keycloak")
public class ExternalSubjectJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ExternalIdentityProvisioner provisioner;
    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    ExternalSubjectJwtConverter(ExternalIdentityProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt external) {
        String subject = external.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("The token has no subject");
        }
        UserId id = provisioner.resolve(
                subject,
                external.getClaimAsString("email"),
                Boolean.TRUE.equals(external.getClaimAsBoolean("email_verified")));
        Jwt internal = Jwt.withTokenValue(external.getTokenValue())
                .headers(headers -> headers.putAll(external.getHeaders()))
                .claims(claims -> {
                    claims.putAll(external.getClaims());
                    claims.put("sub", id.value().toString());
                    claims.put("external_sub", subject);
                })
                .build();
        return delegate.convert(internal);
    }
}
