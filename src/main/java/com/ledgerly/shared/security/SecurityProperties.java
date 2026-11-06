package com.ledgerly.shared.security;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param allowedOrigins the browser origins that may call the API from JavaScript (CORS), for example
 *     {@code https://app.example.com}. Empty by default: no other site may read our responses. Exact origins only: a
 *     wildcard, a path or a trailing slash stops the application from starting, because a too-wide allowlist is a
 *     security hole that looks like a working feature.
 */
@Validated
@ConfigurationProperties("ledgerly.security")
public record SecurityProperties(@DefaultValue List<String> allowedOrigins) {

    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    public SecurityProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            if (!ORIGIN.matcher(origin).matches()) {
                throw new IllegalArgumentException(
                        "ledgerly.security.allowed-origins must hold exact origins like https://app.example.com, but got '"
                                + origin + "'");
            }
        }
    }
}
