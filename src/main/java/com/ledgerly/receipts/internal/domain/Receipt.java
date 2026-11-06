package com.ledgerly.receipts.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A file attached to an expense as evidence. The original name is metadata only; storage uses a random key. */
@Entity
@Table(name = "receipts")
public class Receipt {

    @Id
    private UUID id;

    private UUID teamId;
    private UUID expenseId;
    private String storageKey;
    private String originalName;
    private String detectedType;
    private long sizeBytes;
    private String sha256;

    @Enumerated(EnumType.STRING)
    private ScanStatus scanStatus;

    private boolean possibleDuplicate;
    private UUID uploadedBy;
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Receipt() {}

    public Receipt(
            UUID teamId,
            UUID expenseId,
            String storageKey,
            String originalName,
            String detectedType,
            long sizeBytes,
            String sha256,
            boolean possibleDuplicate,
            UUID uploadedBy,
            Instant now) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.teamId = teamId;
        this.expenseId = expenseId;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.detectedType = detectedType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.scanStatus = ScanStatus.PENDING_SCAN;
        this.possibleDuplicate = possibleDuplicate;
        this.uploadedBy = uploadedBy;
        this.createdAt = now;
    }

    public void accept() {
        scanStatus = ScanStatus.ACCEPTED;
    }

    /** Infected files are never served; their object moves to another key so nothing can reach it by accident. */
    public void quarantine(String quarantineKey) {
        scanStatus = ScanStatus.QUARANTINED;
        storageKey = quarantineKey;
    }

    public boolean isDownloadable() {
        return scanStatus == ScanStatus.ACCEPTED;
    }

    public UUID id() {
        return id;
    }

    public UUID teamId() {
        return teamId;
    }

    public UUID expenseId() {
        return expenseId;
    }

    public String storageKey() {
        return storageKey;
    }

    public String originalName() {
        return originalName;
    }

    public String detectedType() {
        return detectedType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public ScanStatus scanStatus() {
        return scanStatus;
    }

    public boolean possibleDuplicate() {
        return possibleDuplicate;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }
}
