package com.ledgerly.imports.internal.infrastructure;

import com.ledgerly.imports.events.ImportCompleted;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.storage.ObjectStorage;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Keeps {@code import_jobs} in step with the Batch job, and announces the end of an import. */
@Component
class ImportJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ImportJobListener.class);

    private final ImportJobs jobs;
    private final ObjectStorage storage;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ImportJobListener(ImportJobs jobs, ObjectStorage storage, ApplicationEventPublisher events, Clock clock) {
        this.jobs = jobs;
        this.storage = storage;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        jobs.markRunning(importId(execution), clock.instant());
    }

    @Override
    public void afterJob(JobExecution execution) {
        UUID importId = importId(execution);
        boolean completed = execution.getStatus() == BatchStatus.COMPLETED;
        jobs.markFinished(importId, completed ? "COMPLETED" : "FAILED", clock.instant());
        jobs.find(importId).ifPresent(job -> {
            if (completed) {
                // The file is no longer needed; a failed import keeps it so it can be restarted.
                try {
                    storage.delete(job.storageKey());
                } catch (RuntimeException e) {
                    log.warn(
                            "Could not delete the file of import {}: {}",
                            importId,
                            e.getClass().getSimpleName());
                }
            }
            events.publishEvent(new ImportCompleted(
                    new TeamId(job.teamId()),
                    new UserId(job.userId()),
                    importId,
                    job.importedRows(),
                    jobs.errorCount(importId),
                    !completed,
                    clock.instant()));
        });
        log.info("Import {} finished: {}", importId, execution.getStatus());
    }

    static UUID importId(JobExecution execution) {
        return UUID.fromString(execution.getJobParameters().getString(ImportJobConfig.PARAM_IMPORT_ID));
    }
}
