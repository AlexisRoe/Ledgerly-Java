package com.ledgerly.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.LedgerlyApplication;
import com.ledgerly.receipts.internal.infrastructure.ReceiptsProperties;
import com.ledgerly.shared.storage.StorageProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The prod profile starts only when it has everything it needs, and says exactly what is missing. */
class ProductionEnvironmentCheckTest {

    static final Map<String, Object> EVERYTHING = Map.ofEntries(
            Map.entry("spring.datasource.url", "jdbc:postgresql://db:5432/ledgerly"),
            Map.entry("spring.datasource.username", "ledgerly"),
            Map.entry("spring.datasource.password", "pw"),
            Map.entry("ledgerly.auth.jwt-private-key", "private"),
            Map.entry("ledgerly.auth.jwt-public-key", "public"),
            Map.entry("ledgerly.storage.s3.endpoint", "https://s3.example.com"),
            Map.entry("ledgerly.storage.s3.bucket", "files"),
            Map.entry("ledgerly.storage.s3.access-key", "key"),
            Map.entry("ledgerly.storage.s3.secret-key", "secret"),
            Map.entry("ledgerly.receipts.clamav.host", "clamav"),
            Map.entry("ledgerly.receipts.clamav.port", "3310"));

    private StandardEnvironment environment(String profile, Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.setActiveProfiles(profile);
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }

    private void check(StandardEnvironment env) {
        new ProductionEnvironmentCheck().postProcessEnvironment(env, null);
    }

    @Test
    void prodWithNothingSetReportsEveryMissingSettingAtOnce() {
        assertThatThrownBy(() -> check(environment("prod", Map.of())))
                .isInstanceOfSatisfying(ProductionConfigurationException.class, e -> {
                    assertThat(e.missing()).hasSize(ProductionEnvironmentCheck.REQUIRED.size());
                    assertThat(e.getMessage())
                            .contains("spring.datasource.url (environment variable SPRING_DATASOURCE_URL)")
                            .contains(
                                    "ledgerly.auth.jwt-private-key (environment variable LEDGERLY_AUTH_JWT_PRIVATE_KEY)")
                            .contains(
                                    "ledgerly.storage.s3.secret-key (environment variable LEDGERLY_STORAGE_S3_SECRET_KEY)")
                            .contains("ledgerly.receipts.clamav.host");
                });
    }

    @Test
    void blankValuesCountAsMissing() {
        var props = new java.util.HashMap<>(EVERYTHING);
        props.put("ledgerly.storage.s3.secret-key", "   ");
        props.put("spring.datasource.password", "");
        assertThatThrownBy(() -> check(environment("prod", props)))
                .isInstanceOfSatisfying(
                        ProductionConfigurationException.class,
                        e -> assertThat(e.missing())
                                .hasSize(2)
                                .anyMatch(m -> m.startsWith("ledgerly.storage.s3.secret-key"))
                                .anyMatch(m -> m.startsWith("spring.datasource.password")));
    }

    @Test
    void prodWithEverythingSetStarts() {
        check(environment("prod", EVERYTHING));
    }

    @Test
    void otherProfilesAreNotChecked() {
        check(environment("local", Map.of()));
        check(environment("test", Map.of()));
    }

    @Test
    void withKeycloakNoSigningKeysAreNeededButTheIssuerIs() {
        var props = new java.util.HashMap<>(EVERYTHING);
        props.remove("ledgerly.auth.jwt-private-key");
        props.remove("ledgerly.auth.jwt-public-key");
        var env = new StandardEnvironment();
        env.setActiveProfiles("prod", "keycloak");
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        assertThatThrownBy(() -> check(env))
                .isInstanceOfSatisfying(
                        ProductionConfigurationException.class,
                        e -> assertThat(e.missing())
                                .singleElement()
                                .asString()
                                .contains("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        props.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://sso.example.com/realms/ledgerly");
        env.getPropertySources().addFirst(new MapPropertySource("more", props));
        check(env);
    }

    @Test
    void theFailureReadsAsAnActionableMessageNotAStackTrace() {
        var analysis = new ProductionConfigurationFailureAnalyzer()
                .analyze(new ProductionConfigurationException(
                        java.util.List.of("spring.datasource.url (environment variable SPRING_DATASOURCE_URL)")));
        assertThat(analysis.getDescription()).contains("SPRING_DATASOURCE_URL");
        assertThat(analysis.getAction()).contains("Set them in the environment");
    }

    // --- the real application ----------------------------------------------------------------------------------------

    @Test
    void theWholeApplicationRefusesToStartInProdWithoutConfiguration() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(LedgerlyApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--spring.profiles.active=prod"))
                .isInstanceOf(ProductionConfigurationException.class)
                .hasMessageContaining("SPRING_DATASOURCE_URL");
    }

    // --- the properties classes Ledgerly owns validate themselves in every profile
    // ------------------------------------------

    @Test
    void objectStoreSettingsHaveNoDefaults() {
        new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(StorageOnly.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("s3.accessKey")
                            .contains("s3.secretKey")
                            .contains("s3.endpoint")
                            .contains("s3.bucket");
                });
    }

    @Test
    void objectStoreSettingsBindWhenSet() {
        new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(StorageOnly.class)
                .withPropertyValues(
                        "ledgerly.storage.s3.endpoint=https://s3.example.com",
                        "ledgerly.storage.s3.bucket=files",
                        "ledgerly.storage.s3.access-key=k",
                        "ledgerly.storage.s3.secret-key=s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var s3 = context.getBean(StorageProperties.class).s3();
                    assertThat(s3.region()).isEqualTo("us-east-1");
                    assertThat(s3.toString()).doesNotContain("secret").contains("credentials=***");
                });
    }

    @Test
    void malwareScannerAddressHasNoDefault() {
        new ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(ReceiptsOnly.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("clamav.host")
                            .contains("clamav.port");
                });
    }

    @EnableConfigurationProperties(StorageProperties.class)
    static class StorageOnly {}

    @EnableConfigurationProperties(ReceiptsProperties.class)
    static class ReceiptsOnly {}

    private static String rootMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
