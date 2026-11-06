package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.expenses.NewExpense;
import com.ledgerly.imports.internal.application.RawRow;
import com.ledgerly.imports.internal.application.RowRejected;
import java.util.UUID;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/** Records every skipped line in {@code import_row_errors}, with the line number the user can find in the file. */
class ImportSkipListener implements SkipListener<RawRow, NewExpense> {

    private final UUID importId;
    private final ImportJobs jobs;

    ImportSkipListener(UUID importId, ImportJobs jobs) {
        this.importId = importId;
        this.jobs = jobs;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException parse) {
            jobs.addError(importId, parse.getLineNumber(), "PARSE_ERROR", "The line could not be read as CSV");
        }
    }

    @Override
    public void onSkipInProcess(RawRow row, Throwable t) {
        if (t instanceof RowRejected rejected) {
            jobs.addError(importId, rejected.line(), rejected.code(), String.valueOf(rejected.getMessage()));
        } else {
            jobs.addError(importId, row.line(), "ROW_INVALID", "The line could not be imported");
        }
    }
}
