package com.ledgerly.receipts.internal.application;

import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.domain.ScanStatus;
import java.util.UUID;

public record ReceiptView(
        UUID id,
        UUID expenseId,
        ScanStatus status,
        String detectedType,
        long sizeBytes,
        String originalName,
        boolean possibleDuplicate) {

    static ReceiptView of(Receipt r) {
        return new ReceiptView(
                r.id(),
                r.expenseId(),
                r.scanStatus(),
                r.detectedType(),
                r.sizeBytes(),
                r.originalName(),
                r.possibleDuplicate());
    }
}
