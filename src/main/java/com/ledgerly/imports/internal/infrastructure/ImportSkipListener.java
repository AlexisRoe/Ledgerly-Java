package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.expenses.NewExpense;
import com.ledgerly.imports.internal.application.RawRow;
import com.ledgerly.imports.internal.application.RowRejected;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/** Records every skipped line in {@code import_row_errors}, with the line number the user can find in the file. */
class ImportSkipListener implements SkipListener<RawRow, NewExpense> {

    private final UUID importId;
    private final ImportJobs jobs;
    private final Counter skipped;

    /** @param importId the import whose rejected lines are recorded */
    ImportSkipListener(UUID importId, ImportJobs jobs, MeterRegistry metrics) {
        this.importId = importId;
        this.jobs = jobs;
        this.skipped = metrics.counter("ledgerly.imports.rows.skipped");
    }

    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof FlatFileParseException parse) {
            jobs.addError(importId, parse.getLineNumber(), "PARSE_ERROR", "The line could not be read as CSV");
            skipped.increment();
        }
    }

    @Override
    public void onSkipInProcess(RawRow row, Throwable t) {
        skipped.increment();
        if (t instanceof RowRejected rejected) {
            jobs.addError(importId, rejected.line(), rejected.code(), String.valueOf(rejected.getMessage()));
        } else {
            jobs.addError(importId, row.line(), "ROW_INVALID", "The line could not be imported");
        }
    }
}
