package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.imports.internal.application.ImportQueued;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts import jobs, once the row that describes them has committed, on a virtual thread. If the application
 * stops between the commit and the start, the import stays QUEUED (the event publication registry of M9 fixes
 * that); if a job fails halfway it can be {@link #restart restarted}.
 */
@Component
public class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final JobOperator operator;
    private final Job job;
    private final JobRepository repository;
    private final ImportJobs jobs;
    private final TaskExecutor executor;
    private final Clock clock;

    ImportRunner(
            JobOperator operator,
            Job importExpensesJob,
            JobRepository repository,
            ImportJobs jobs,
            @Qualifier("importExecutor") TaskExecutor executor,
            Clock clock) {
        this.operator = operator;
        this.job = importExpensesJob;
        this.repository = repository;
        this.jobs = jobs;
        this.executor = executor;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ImportQueued event) {
        executor.execute(() -> run(event.importId()));
    }

    /** Runs the job to its end on the calling thread. Never throws: a failed launch is recorded as FAILED. */
    void run(UUID importId) {
        try {
            operator.start(job, parameters(importId));
        } catch (Exception e) {
            log.error(
                    "Import {} could not be started: {}", importId, e.getClass().getSimpleName());
            jobs.markFinished(importId, "FAILED", clock.instant());
        }
    }

    /**
     * Resumes a FAILED import from the last committed chunk: lines already imported are not read or written
     * again. Returns false when there is nothing to restart.
     */
    public boolean restart(UUID importId) {
        var row = jobs.find(importId);
        if (row.isEmpty() || !"FAILED".equals(row.get().status())) {
            return false;
        }
        try {
            JobExecution last = repository.getLastJobExecution(ImportJobConfig.JOB_NAME, parameters(importId));
            if (last == null) {
                return false;
            }
            operator.restart(last);
            return true;
        } catch (Exception e) {
            log.error(
                    "Import {} could not be restarted: {}",
                    importId,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private JobParameters parameters(UUID importId) {
        var row = jobs.find(importId).orElseThrow();
        // Only the import id identifies the job instance; the rest is information for the steps.
        return new JobParametersBuilder()
                .addString(ImportJobConfig.PARAM_IMPORT_ID, importId.toString(), true)
                .addString(ImportJobConfig.PARAM_TEAM_ID, row.teamId().toString(), false)
                .addString(ImportJobConfig.PARAM_USER_ID, row.userId().toString(), false)
                .addString(ImportJobConfig.PARAM_STORAGE_KEY, row.storageKey(), false)
                .toJobParameters();
    }
}
