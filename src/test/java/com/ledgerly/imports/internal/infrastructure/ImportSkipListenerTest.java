package com.ledgerly.imports.internal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.imports.internal.application.RawRow;
import com.ledgerly.imports.internal.application.RowRejected;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** What a user is told about the lines that were skipped, and what they must not be told. */
@SpringBootTest(properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ImportSkipListenerTest {

    @Autowired
    ImportJobs jobs;

    UUID importId;
    SimpleMeterRegistry metrics;
    ImportSkipListener listener;

    @BeforeEach
    void newImport() {
        importId = UUID.randomUUID();
        jobs.insert(new ImportJobs.Job(
                importId, UUID.randomUUID(), UUID.randomUUID(), "RUNNING", "x.csv", "imports/x", 10, 0, Instant.now()));
        metrics = new SimpleMeterRegistry();
        listener = new ImportSkipListener(importId, jobs, metrics);
    }

    double skipped() {
        return metrics.counter("ledgerly.imports.rows.skipped").count();
    }

    @Test
    void aRejectedLineIsReportedWithItsOwnLineNumberCodeAndMessage() {
        listener.onSkipInProcess(
                new RawRow(7, List.of("x")), new RowRejected(7, "INVALID_AMOUNT", "Amount must be positive"));

        assertThat(jobs.errors(importId, 10))
                .containsExactly(new ImportJobs.RowError(7, "INVALID_AMOUNT", "Amount must be positive"));
        assertThat(skipped()).isEqualTo(1.0);
    }

    @Test
    void anUnexpectedFailureNeverLeaksItsMessageToTheUser() {
        var internal = new IllegalStateException("jdbc:postgresql://db.internal:5432/ledgerly password=hunter2");

        listener.onSkipInProcess(new RawRow(3, List.of("x")), internal);

        var errors = jobs.errors(importId, 10);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().line()).isEqualTo(3);
        assertThat(errors.getFirst().code()).isEqualTo("ROW_INVALID");
        assertThat(errors.getFirst().message()).doesNotContain("hunter2").doesNotContain("db.internal");
    }

    @Test
    void aLineThatCannotBeParsedIsReportedAsAParseErrorAtItsLine() {
        listener.onSkipInRead(new FlatFileParseException("bad quote", "a,\"b", 12));

        assertThat(jobs.errors(importId, 10))
                .extracting(ImportJobs.RowError::line, ImportJobs.RowError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(12, "PARSE_ERROR"));
        assertThat(skipped()).isEqualTo(1.0);
    }

    @Test
    void aReadFailureThatIsNotAboutOneLineIsNotReportedAsALineError() {
        listener.onSkipInRead(new IOException("connection reset"));

        assertThat(jobs.errors(importId, 10)).isEmpty();
        assertThat(skipped()).as("not a skipped line").isZero();
    }

    @Test
    void everySkippedLineIsCounted() {
        for (int line = 2; line <= 6; line++) {
            listener.onSkipInProcess(new RawRow(line, List.of()), new RowRejected(line, "X", "m"));
        }

        assertThat(skipped()).isEqualTo(5.0);
        assertThat(jobs.errorCount(importId)).isEqualTo(5);
    }

    @Test
    void aRestartedImportReportingTheSameLineAgainKeepsOneRow() {
        var rejected = new RowRejected(4, "INVALID_DATE", "Date is in the future");

        listener.onSkipInProcess(new RawRow(4, List.of()), rejected);
        listener.onSkipInProcess(new RawRow(4, List.of()), rejected);

        assertThat(jobs.errorCount(importId)).isEqualTo(1);
    }

    @Test
    void aVeryLongMessageIsCutToWhatTheColumnHolds() {
        listener.onSkipInProcess(new RawRow(5, List.of()), new RowRejected(5, "X", "y".repeat(2000)));

        assertThat(jobs.errors(importId, 10).getFirst().message()).hasSize(500);
    }
}
