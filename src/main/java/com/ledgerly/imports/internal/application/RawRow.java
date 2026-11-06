package com.ledgerly.imports.internal.application;

import java.util.List;

/** One CSV line, split into fields but not yet interpreted. {@code line} is the line number in the file (header = 1). */
public record RawRow(int line, List<String> fields) {

    public boolean isBlank() {
        return fields.stream().allMatch(String::isBlank);
    }
}
