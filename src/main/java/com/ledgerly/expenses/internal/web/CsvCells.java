package com.ledgerly.expenses.internal.web;

/**
 * Formatting for the CSV export. The security point is {@link #cell}: a spreadsheet treats a cell that starts with
 * {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage return as a formula, so a merchant called
 * {@code =HYPERLINK(...)} could run in an approver's Excel. Such cells get a leading quote, which makes the
 * spreadsheet show them as plain text.
 */
final class CsvCells {

    private CsvCells() {}

    /** One CSV field: formula-neutralised, and quoted when it holds a comma, quote or line break. */
    static String cell(String value) {
        String text = neutralise(value);
        boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        if (!needsQuotes) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** Prefixes a quote to values that start like a spreadsheet formula, so they show as text. */
    static String neutralise(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + value;
            default -> value;
        };
    }

    /** One CSV line (CRLF-terminated) from the given values. */
    static String row(String... cells) {
        var line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) line.append(',');
            line.append(cell(cells[i]));
        }
        return line.append("\r\n").toString();
    }
}
