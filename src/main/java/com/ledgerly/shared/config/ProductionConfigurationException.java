package com.ledgerly.shared.config;

import java.util.List;

/** The {@code prod} profile was started without settings it cannot run without. */
public class ProductionConfigurationException extends RuntimeException {

    private final List<String> missing;

    public ProductionConfigurationException(List<String> missing) {
        super("Missing settings for the prod profile: " + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }
}
