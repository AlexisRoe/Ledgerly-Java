package com.ledgerly.shared.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns the exception into Spring Boot's "APPLICATION FAILED TO START" block instead of a stack trace. */
class ProductionConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<ProductionConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ProductionConfigurationException cause) {
        StringBuilder description = new StringBuilder("The prod profile needs these settings, which are not set:\n");
        cause.missing().forEach(m -> description.append("\n  - ").append(m));
        return new FailureAnalysis(
                description.toString(),
                "Set them in the environment of the process (or the container). Nothing in prod has a default: a made-up "
                        + "database, key or bucket would be worse than not starting.",
                cause);
    }
}
