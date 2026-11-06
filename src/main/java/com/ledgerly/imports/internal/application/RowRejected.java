package com.ledgerly.imports.internal.application;

/** A line of the CSV that cannot become an expense. Skippable: the import goes on with the next line. */
public class RowRejected extends RuntimeException {

    private final int line;
    private final String code;

    /**
     * @param line line number in the file
     * @param code stable error code shown to the user
     * @param message what is wrong with the line
     */
    public RowRejected(int line, String code, String message) {
        super(message);
        this.line = line;
        this.code = code;
    }

    /** Line number in the uploaded file (header = 1). */
    public int line() {
        return line;
    }

    /** Stable error code for the line. */
    public String code() {
        return code;
    }
}
