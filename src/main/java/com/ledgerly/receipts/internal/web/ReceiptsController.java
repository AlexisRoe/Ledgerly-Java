package com.ledgerly.receipts.internal.web;

import com.ledgerly.receipts.internal.application.DeleteReceipt;
import com.ledgerly.receipts.internal.application.DownloadReceipt;
import com.ledgerly.receipts.internal.application.ListReceipts;
import com.ledgerly.receipts.internal.application.ReceiptView;
import com.ledgerly.receipts.internal.application.UploadReceipt;
import com.ledgerly.shared.api.ReceiptsApi;
import com.ledgerly.shared.api.model.DownloadLink;
import com.ledgerly.shared.api.model.ReceiptResponse;
import com.ledgerly.shared.api.model.ScanStatus;
import com.ledgerly.shared.error.UnsupportedMedia;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.security.CurrentUser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload, list, download-link and delete under /api/v1/teams/{teamId}/expenses/{expenseId}/receipts. Contract: api/openapi.yaml.
 */
@RestController
@RequestMapping("/api/v1")
class ReceiptsController implements ReceiptsApi {

    private final CurrentUser currentUser;
    private final UploadReceipt upload;
    private final ListReceipts list;
    private final DeleteReceipt delete;
    private final DownloadReceipt download;

    ReceiptsController(
            CurrentUser currentUser,
            UploadReceipt upload,
            ListReceipts list,
            DeleteReceipt delete,
            DownloadReceipt download) {
        this.currentUser = currentUser;
        this.upload = upload;
        this.list = list;
        this.delete = delete;
        this.download = download;
    }

    @Override
    public ResponseEntity<ReceiptResponse> uploadReceipt(UUID teamId, UUID expenseId, MultipartFile file) {
        ReceiptView view;
        try {
            view = upload.execute(new UploadReceipt.Command(
                    new TeamId(teamId),
                    new ExpenseId(expenseId),
                    currentUser.id(),
                    file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename(),
                    file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/teams/" + teamId + "/expenses/" + expenseId + "/receipts"))
                .body(toResponse(view));
    }

    @Override
    public ResponseEntity<List<ReceiptResponse>> listReceipts(UUID teamId, UUID expenseId) {
        return ResponseEntity.ok(list.execute(new TeamId(teamId), new ExpenseId(expenseId), currentUser.id()).stream()
                .map(ReceiptsController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<Void> deleteReceipt(UUID teamId, UUID expenseId, UUID receiptId) {
        delete.execute(new TeamId(teamId), new ExpenseId(expenseId), receiptId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DownloadLink> downloadReceipt(UUID teamId, UUID expenseId, UUID receiptId) {
        var link = download.execute(new TeamId(teamId), new ExpenseId(expenseId), receiptId, currentUser.id());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new DownloadLink(link.url().toString(), link.expiresAt().atOffset(ZoneOffset.UTC)));
    }

    private static ReceiptResponse toResponse(ReceiptView v) {
        return new ReceiptResponse(
                v.id(),
                v.expenseId(),
                ScanStatus.valueOf(v.status().name()),
                detectedType(v.detectedType()),
                v.sizeBytes(),
                v.originalName(),
                v.possibleDuplicate());
    }

    private static ReceiptResponse.DetectedTypeEnum detectedType(String mime) {
        return switch (mime) {
            case "application/pdf" -> ReceiptResponse.DetectedTypeEnum.APPLICATION_PDF;
            case "image/jpeg" -> ReceiptResponse.DetectedTypeEnum.IMAGE_JPEG;
            case "image/png" -> ReceiptResponse.DetectedTypeEnum.IMAGE_PNG;
            default -> throw new UnsupportedMedia("Unknown stored type");
        };
    }
}
