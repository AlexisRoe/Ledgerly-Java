package com.ledgerly.receipts.internal.application;

import com.ledgerly.receipts.events.ReceiptEvent;
import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.domain.ScanStatus;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.storage.ObjectStorage;
import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scans a freshly stored receipt. Runs after the upload commits, on another thread. If the application stops
 * between the commit and the scan, or the scanner stays unreachable, the receipt remains PENDING_SCAN: nothing
 * re-triggers it yet (the event publication registry of M9 fixes that).
 */
@Component
public class ScanReceipt {

    private static final Logger log = LoggerFactory.getLogger(ScanReceipt.class);

    private final ReceiptRepository receipts;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    ScanReceipt(
            ReceiptRepository receipts,
            ObjectStorage storage,
            MalwareScanner scanner,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Clock clock) {
        this.receipts = receipts;
        this.storage = storage;
        this.scanner = scanner;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ReceiptUploaded event) {
        scan(event.receiptId());
    }

    /** Public so a future recovery job (or a test) can scan a receipt that is still pending. */
    public void scan(UUID receiptId) {
        Optional<Receipt> found = tx.execute(s -> receipts.findById(receiptId));
        if (found == null || found.isEmpty() || found.get().scanStatus() != ScanStatus.PENDING_SCAN) {
            return;
        }
        Receipt receipt = found.get();
        MalwareScanner.Result result;
        try (InputStream content = storage.open(receipt.storageKey())) {
            result = scanner.scan(content);
        } catch (Exception e) {
            log.warn(
                    "Scan of receipt {} failed ({}); it stays PENDING_SCAN",
                    receiptId,
                    e.getClass().getSimpleName());
            return;
        }
        switch (result) {
            case MalwareScanner.Result.Clean clean -> markAccepted(receipt);
            case MalwareScanner.Result.Infected infected -> quarantine(receipt, infected.signature());
        }
    }

    private void markAccepted(Receipt scanned) {
        tx.executeWithoutResult(s -> {
            Receipt receipt = receipts.findById(scanned.id()).orElseThrow();
            receipt.accept();
            events.publishEvent(new ReceiptEvent.Accepted(
                    new TeamId(receipt.teamId()), new ExpenseId(receipt.expenseId()), receipt.id(), clock.instant()));
        });
    }

    private void quarantine(Receipt scanned, String signature) {
        String quarantineKey = "quarantine/" + UUID.randomUUID();
        storage.move(scanned.storageKey(), quarantineKey);
        tx.executeWithoutResult(s -> {
            Receipt receipt = receipts.findById(scanned.id()).orElseThrow();
            receipt.quarantine(quarantineKey);
            events.publishEvent(new ReceiptEvent.Quarantined(
                    new TeamId(receipt.teamId()), new ExpenseId(receipt.expenseId()), receipt.id(), clock.instant()));
        });
        log.warn("Receipt {} quarantined: scanner reported {}", scanned.id(), signature);
    }
}
