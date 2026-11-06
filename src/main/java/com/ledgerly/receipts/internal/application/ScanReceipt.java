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
 * Scans a freshly stored receipt. Runs after the upload commits, on another thread, as a tracked event listener:
 * Spring Modulith stores a publication for it in the same transaction as the upload and completes it when this
 * method returns normally. If the application stops before the scan, or the scanner cannot be reached, the method
 * does not complete, the publication stays open, and the event is delivered again at the next start-up (or when
 * incomplete publications are resubmitted). The receipt stays PENDING_SCAN in the meantime.
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

    /**
     * Scans one receipt if it is still pending; does nothing otherwise, so delivering the same event twice is
     * harmless.
     *
     * @throws IllegalStateException when the file or the scanner cannot be reached: the caller (the event
     *     registry) must see the failure, because swallowing it would mark the event as handled
     */
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
            throw new IllegalStateException("Scan of receipt " + receiptId + " failed", e);
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
        String originalKey = scanned.storageKey();
        // The row changes first. If moving the object then fails, the receipt is already QUARANTINED (never
        // served) and a re-delivered event finds nothing left to do; at worst a stray object stays under its
        // old key, unreachable from the API.
        tx.executeWithoutResult(s -> {
            Receipt receipt = receipts.findById(scanned.id()).orElseThrow();
            receipt.quarantine(quarantineKey);
            events.publishEvent(new ReceiptEvent.Quarantined(
                    new TeamId(receipt.teamId()), new ExpenseId(receipt.expenseId()), receipt.id(), clock.instant()));
        });
        log.warn("Receipt {} quarantined: scanner reported {}", scanned.id(), signature);
        storage.move(originalKey, quarantineKey);
    }
}
