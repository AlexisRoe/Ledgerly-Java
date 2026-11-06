package com.ledgerly.identity.internal.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Verifies Keycloak's tokens: signature (keys fetched from the issuer), issuer, expiry (against our {@link Clock}) and
 * audience. The decoder is built on first use, so Ledgerly can start while Keycloak is still starting.
 */
@Configuration(proxyBeanMethods = false)
@Profile("keycloak")
class KeycloakResourceServerConfig {

    private static final Logger log = LoggerFactory.getLogger(KeycloakResourceServerConfig.class);

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${ledgerly.auth.keycloak.audience:ledgerly-api}") String audience,
            Clock clock) {
        JwtDecoder lazy = new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
            timestamps.setClock(clock);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    timestamps, new JwtIssuerValidator(issuer), new AudienceValidator(audience)));
            return decoder;
        });
        return token -> {
            try {
                return lazy.decode(token);
            } catch (JwtException e) {
                throw e;
            } catch (RuntimeException e) {
                // The identity provider cannot be reached (still starting, or down): not a server error with a stack
                // trace, but a refused token and one line in the log. The next request tries again.
                log.warn(
                        "Cannot verify a token: the identity provider is not reachable ({})",
                        e.getClass().getSimpleName());
                // BadJwtException, not plain JwtException: Spring Security treats the latter as a server fault and lets
                // it
                // escape the filter chain as an unhandled exception. This way the answer is a normal 401 (fail closed).
                throw new BadJwtException("The identity provider is not reachable", e);
            }
        };
    }

    /** A token issued for another application of the same realm is not a token for this API. */
    static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String audience;

        AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audiences = jwt.getAudience();
            if (audiences != null && audiences.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The token is not meant for this API", null));
        }
    }
}
