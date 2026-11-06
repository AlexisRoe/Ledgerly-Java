package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.imports.internal.application.RawRow;
import com.ledgerly.imports.internal.application.RowProcessor;
import com.ledgerly.imports.internal.application.RowRejected;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.LineMapper;
import org.springframework.batch.infrastructure.item.file.separator.SimpleRecordSeparatorPolicy;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The import job: read a line, turn it into a {@code NewExpense} (rejecting bad lines one by one), and write
 * the good ones 100 at a time through {@code ExpensesFacade.createDrafts}. Each chunk is one transaction, so a
 * failure in the middle leaves whole chunks committed and the job restartable from the last one.
 */
@Configuration(proxyBeanMethods = false)
class ImportJobConfig {

    static final String JOB_NAME = "importExpenses";
    static final String PARAM_IMPORT_ID = "importId";
    static final String PARAM_TEAM_ID = "teamId";
    static final String PARAM_USER_ID = "userId";
    static final String PARAM_STORAGE_KEY = "storageKey";

    /** The single job; its listener keeps the status in {@code import_jobs} in step. */
    @Bean
    Job importExpensesJob(JobRepository repository, Step importStep, ImportJobListener listener) {
        return new JobBuilder(JOB_NAME, repository)
                .listener(listener)
                .start(importStep)
                .build();
    }

    @Bean
    Step importStep(
            JobRepository repository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<RawRow> importReader,
            RowProcessor processor,
            ItemWriter<NewExpense> importWriter,
            ImportSkipListener importSkipListener,
            LedgerlyLimits limits) {
        return new StepBuilder("importStep", repository)
                .<RawRow, NewExpense>chunk(limits.maxBatchSize(), transactionManager)
                .reader(importReader)
                .processor(processor)
                .writer(importWriter)
                .faultTolerant()
                // Bad lines are skipped and reported; anything else (a database failure) stops the job.
                .skip(RowRejected.class)
                .skip(FlatFileParseException.class)
                .skipLimit(limits.maxImportRows())
                .listener(importSkipListener)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<RawRow> importReader(
            ObjectStorage storage, @Value("#{jobParameters['" + PARAM_STORAGE_KEY + "']}") String storageKey) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer(",");
        tokenizer.setQuoteCharacter('"');
        LineMapper<RawRow> lineMapper = (line, lineNumber) ->
                new RawRow(lineNumber, List.of(tokenizer.tokenize(line).getValues()));
        FlatFileItemReader<RawRow> reader =
                new FlatFileItemReader<>(new StorageResource(storage, storageKey), lineMapper);
        reader.setName("importReader");
        reader.setEncoding(StandardCharsets.UTF_8.name());
        reader.setLinesToSkip(1); // the header; it was checked when the file was uploaded
        // One physical line is one record, so a stray quote cannot swallow the rest of the file and
        // the line numbers in the error report stay true.
        reader.setRecordSeparatorPolicy(new SimpleRecordSeparatorPolicy());
        return reader;
    }

    @Bean
    @StepScope
    ItemWriter<NewExpense> importWriter(
            ExpensesFacade expenses,
            ImportJobs jobs,
            @Value("#{jobParameters['" + PARAM_TEAM_ID + "']}") String teamId,
            @Value("#{jobParameters['" + PARAM_USER_ID + "']}") String userId,
            @Value("#{jobParameters['" + PARAM_IMPORT_ID + "']}") String importId) {
        return chunk -> {
            List<NewExpense> items = List.copyOf(chunk.getItems());
            expenses.createDrafts(new TeamId(UUID.fromString(teamId)), new UserId(UUID.fromString(userId)), items);
            // Same transaction as the inserts above: a chunk that rolls back is not counted.
            jobs.addImported(UUID.fromString(importId), items.size());
        };
    }

    @Bean
    @StepScope
    ImportSkipListener importSkipListener(
            ImportJobs jobs,
            MeterRegistry metrics,
            @Value("#{jobParameters['" + PARAM_IMPORT_ID + "']}") String importId) {
        return new ImportSkipListener(UUID.fromString(importId), jobs, metrics);
    }
}
