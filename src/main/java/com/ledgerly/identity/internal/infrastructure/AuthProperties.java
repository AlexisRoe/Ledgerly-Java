package com.ledgerly.identity.internal.infrastructure;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtPrivateKey PKCS#8 EC (P-256) private key, PEM or bare Base64. Required outside local and test.
 * @param jwtPublicKey the matching X.509 public key
 */
@Validated
@ConfigurationProperties("ledgerly.auth")
public record AuthProperties(
        @DefaultValue("10m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("24h") Duration verificationTtl,
        @DefaultValue("5") @Positive int maxFailedLogins,
        @DefaultValue("15m") Duration lockDuration,
        @Nullable String jwtPrivateKey,
        @Nullable String jwtPublicKey) {}
