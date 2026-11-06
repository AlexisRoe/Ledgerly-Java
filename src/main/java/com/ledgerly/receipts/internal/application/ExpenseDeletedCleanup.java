package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.events.ExpenseDeleted;
import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When an expense is deleted, removes its receipts and their files. Runs after the deletion commits. If the
 * application dies in between, the receipts are orphaned (M9's event registry removes that gap).
 */
@Component
class ExpenseDeletedCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExpenseDeletedCleanup.class);

    private final ReceiptRepository receipts;
    private final ReceiptStorage storage;
    private final TransactionTemplate newTx;

    ExpenseDeletedCleanup(ReceiptRepository receipts, ReceiptStorage storage, PlatformTransactionManager txManager) {
        this.receipts = receipts;
        this.storage = storage;
        // The original transaction has already committed; database work in this callback needs a new one.
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(ExpenseDeleted event) {
        List<String> keys = newTx.execute(s -> {
            List<Receipt> found = receipts.findByExpenseId(event.expenseId().value());
            receipts.deleteAll(found);
            return found.stream().map(Receipt::storageKey).toList();
        });
        if (keys == null) {
            return;
        }
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
