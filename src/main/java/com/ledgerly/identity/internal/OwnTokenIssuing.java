package com.ledgerly.identity.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * Marks the parts of identity that exist only while Ledgerly issues its own tokens: registration, verification,
 * login, refresh, logout, the signing keys and the JWKS endpoint. The {@code keycloak} profile switches all of them
 * off, and Keycloak does those jobs instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Profile("!keycloak")
public @interface OwnTokenIssuing {}
