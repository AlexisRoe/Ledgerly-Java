package com.ledgerly.imports.internal.application;

import com.ledgerly.imports.internal.infrastructure.ImportJobs;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.role.Role;
import com.ledgerly.teams.TeamsFacade;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetImport {

    static final int MAX_ERRORS_SHOWN = 1000;

    private final TeamsFacade teams;
    private final ImportJobs jobs;

    GetImport(TeamsFacade teams, ImportJobs jobs) {
        this.teams = teams;
        this.jobs = jobs;
    }

    /**
     * Visible to the user who started the import and to the team's OWNERs. Everyone else, in the team or not,
     * gets the same 404 as for an id that does not exist.
     */
    @Transactional(readOnly = true)
    public ImportView execute(TeamId teamId, UUID importId, UserId caller) {
        Role role = teams.roleOf(teamId, caller).orElseThrow(NotFound::new);
        ImportJobs.Job job = jobs.find(importId)
                .filter(j -> j.teamId().equals(teamId.value()))
                .filter(j -> j.userId().equals(caller.value()) || role == Role.OWNER)
                .orElseThrow(NotFound::new);
        int skipped = jobs.errorCount(importId);
        var errors = jobs.errors(importId, MAX_ERRORS_SHOWN);
        return new ImportView(
                job.id(), job.status(), job.totalRows(), job.importedRows(), skipped, errors, skipped > errors.size());
    }
}
