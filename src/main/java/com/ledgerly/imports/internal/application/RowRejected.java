package com.ledgerly.imports.internal.application;

/** A line of the CSV that cannot become an expense. Skippable: the import goes on with the next line. */
public class RowRejected extends RuntimeException {

    private final int line;
    private final String code;

    public RowRejected(int line, String code, String message) {
        super(message);
        this.line = line;
        this.code = code;
    }

    public int line() {
        return line;
    }

    public String code() {
        return code;
    }
}
