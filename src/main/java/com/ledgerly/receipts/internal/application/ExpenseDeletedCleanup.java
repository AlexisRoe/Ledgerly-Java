package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.storage.ObjectStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * When an expense is deleted, removes its receipts and their files. A Spring Modulith application-module listener:
 * it runs after the deletion commits, on its own thread and in its own transaction, and the event is kept in the
 * publication registry until this method completes, so a crash in between no longer orphans the receipts: the
 * event is delivered again.
 */
@Component
class ExpenseDeletedCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExpenseDeletedCleanup.class);

    private final ReceiptRepository receipts;
    private final ObjectStorage storage;

    ExpenseDeletedCleanup(ReceiptRepository receipts, ObjectStorage storage) {
        this.receipts = receipts;
        this.storage = storage;
    }

    /**
     * Deletes the expense's receipt rows, then their files; a file that cannot be removed is logged and not retried.
     */
    @ApplicationModuleListener
    void on(ExpenseEvent.Deleted event) {
        List<Receipt> found = receipts.findByExpenseId(event.expenseId().value());
        List<String> keys = found.stream().map(Receipt::storageKey).toList();
        receipts.deleteAll(found);
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not delete an object of deleted expense {}: {}",
                        event.expenseId(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
