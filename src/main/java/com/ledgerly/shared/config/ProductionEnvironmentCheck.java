package com.ledgerly.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * With the {@code prod} profile, refuses to start unless every setting that has no safe default is present. It runs
 * before any bean exists, so the application never gets as far as connecting to something with a made-up default or
 * generating a throw-away signing key. All missing settings are reported together, with the environment variable
 * that sets each one, so a deployment is fixed in one go.
 *
 * <p>The properties classes that Ledgerly owns also carry {@code @NotBlank}; this check adds the settings that belong
 * to Spring Boot itself (the database) and the signing keys, and gives one readable message.
 */
public class ProductionEnvironmentCheck implements EnvironmentPostProcessor {

    /** The settings, in the order they are reported. */
    static final List<String> REQUIRED = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "ledgerly.auth.jwt-private-key",
            "ledgerly.auth.jwt-public-key",
            "ledgerly.storage.s3.endpoint",
            "ledgerly.storage.s3.bucket",
            "ledgerly.storage.s3.access-key",
            "ledgerly.storage.s3.secret-key",
            "ledgerly.receipts.clamav.host",
            "ledgerly.receipts.clamav.port");

    /** Whether the Keycloak profile replaces our own token issuing: then no signing keys are needed. */
    private static final Profiles KEYCLOAK = Profiles.of("keycloak");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String property : REQUIRED) {
            if (property.startsWith("ledgerly.auth.jwt-") && environment.acceptsProfiles(KEYCLOAK)) {
                continue;
            }
            String value = environment.getProperty(property);
            if (value == null || value.isBlank()) {
                missing.add(property + " (environment variable " + environmentVariable(property) + ")");
            }
        }
        if (environment.acceptsProfiles(KEYCLOAK)) {
            String issuer = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
            if (issuer == null || issuer.isBlank()) {
                missing.add("spring.security.oauth2.resourceserver.jwt.issuer-uri (environment variable "
                        + environmentVariable("spring.security.oauth2.resourceserver.jwt.issuer-uri") + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new ProductionConfigurationException(missing);
        }
    }

    static String environmentVariable(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
