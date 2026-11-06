package com.ledgerly.receipts.internal.application;

import java.util.Map;

/** Turns whatever the client called the file into something safe to put in a download header. */
final class FileNames {

    private static final Map<String, String> EXTENSIONS =
            Map.of("application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png");

    private FileNames() {}

    /**
     * Keeps only letters, digits, dash and underscore; drops any directory part and leading separators; caps the
     * length; and makes sure the extension matches the real (detected) type rather than the claimed one.
     */
    static String sanitize(String original, String detectedType) {
        String name = original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("^[_-]+", "");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        if (base.isBlank()) {
            base = "receipt";
        }
        return base + "." + EXTENSIONS.getOrDefault(detectedType, "bin");
    }
}
