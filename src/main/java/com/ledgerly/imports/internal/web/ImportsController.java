package com.ledgerly.imports.internal.web;

import com.ledgerly.imports.internal.application.GetImport;
import com.ledgerly.imports.internal.application.ImportView;
import com.ledgerly.imports.internal.application.UploadImport;
import com.ledgerly.shared.api.ImportsApi;
import com.ledgerly.shared.api.model.ImportResponse;
import com.ledgerly.shared.api.model.ImportRowError;
import com.ledgerly.shared.api.model.ImportStatus;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.security.CurrentUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** POST an import (multipart CSV) and poll it under /api/v1/teams/{teamId}/imports. Contract: api/openapi.yaml. */
@RestController
@RequestMapping("/api/v1")
class ImportsController implements ImportsApi {

    private final CurrentUser currentUser;
    private final UploadImport upload;
    private final GetImport get;

    ImportsController(CurrentUser currentUser, UploadImport upload, GetImport get) {
        this.currentUser = currentUser;
        this.upload = upload;
        this.get = get;
    }

    @Override
    public ResponseEntity<ImportResponse> uploadImport(UUID teamId, MultipartFile file) {
        TeamId team = new TeamId(teamId);
        UUID id;
        try {
            id = upload.execute(new UploadImport.Command(
                    team,
                    currentUser.id(),
                    file.getOriginalFilename() == null ? "import.csv" : file.getOriginalFilename(),
                    file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/teams/" + teamId + "/imports/" + id))
                .body(toResponse(get.execute(team, id, currentUser.id())));
    }

    @Override
    public ResponseEntity<ImportResponse> getImport(UUID teamId, UUID importId) {
        return ResponseEntity.ok(toResponse(get.execute(new TeamId(teamId), importId, currentUser.id())));
    }

    private static ImportResponse toResponse(ImportView v) {
        return new ImportResponse(
                v.id(),
                ImportStatus.valueOf(v.status()),
                v.totalRows(),
                v.importedRows(),
                v.skippedRows(),
                v.errors().stream()
                        .map(e -> new ImportRowError(e.line(), e.code(), e.message()))
                        .toList(),
                v.errorsTruncated());
    }
}
