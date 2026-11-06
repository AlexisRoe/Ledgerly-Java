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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts import jobs once the row that describes them has committed. A tracked event listener: Spring Modulith
 * keeps the {@link ImportQueued} event until this method returns, so an import that was queued but never started
 * because the application stopped is started at the next start-up. The job runs to its end on this (virtual)
 * thread, not inside a listener transaction, so every chunk still commits on its own. A job that fails halfway
 * can be {@link #restart restarted}.
 */
@Component
public class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final JobOperator operator;
    private final Job job;
    private final JobRepository repository;
    private final ImportJobs jobs;
    private final Clock clock;

    ImportRunner(JobOperator operator, Job importExpensesJob, JobRepository repository, ImportJobs jobs, Clock clock) {
        this.operator = operator;
        this.job = importExpensesJob;
        this.repository = repository;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ImportQueued event) {
        run(event.importId());
    }

    /**
     * Runs the job to its end on the calling thread. Never throws: a failed launch is recorded as FAILED. Safe to
     * call again for the same import: one that is already finished or running is left alone.
     */
    void run(UUID importId) {
        var row = jobs.find(importId);
        if (row.isEmpty()) {
            return;
        }
        switch (row.get().status()) {
            case "QUEUED" -> start(importId);
            case "FAILED" -> restart(importId);
            default ->
                log.info(
                        "Import {} is {}; nothing to start", importId, row.get().status());
        }
    }

    private void start(UUID importId) {
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
