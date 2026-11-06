package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.receipts.events.ReceiptEvent;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.storage.ObjectStorage;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Lets the claimant remove a receipt from their draft expense; the file goes with it. */
@Service
public class DeleteReceipt {

    private static final Logger log = LoggerFactory.getLogger(DeleteReceipt.class);

    private final ExpensesFacade expenses;
    private final ReceiptRepository receipts;
    private final ObjectStorage storage;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    DeleteReceipt(
            ExpensesFacade expenses,
            ReceiptRepository receipts,
            ObjectStorage storage,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Clock clock) {
        this.expenses = expenses;
        this.receipts = receipts;
        this.storage = storage;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** @throws com.ledgerly.shared.error.NotFound if the receipt is not on that expense */
    public void execute(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId caller) {
        expenses.requireCanChangeReceipts(teamId, expenseId, caller);
        String key = tx.execute(s -> {
            var receipt = receipts.findByIdAndTeamIdAndExpenseId(receiptId, teamId.value(), expenseId.value())
                    .orElseThrow(NotFound::new);
            receipts.delete(receipt);
            events.publishEvent(new ReceiptEvent.Deleted(teamId, expenseId, receiptId, caller, clock.instant()));
            return receipt.storageKey();
        });
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            // The row is gone, so the file is unreachable; a leftover object is harmless and can be swept later.
            log.warn(
                    "Could not delete object of receipt {}: {}",
                    receiptId,
                    e.getClass().getSimpleName());
        }
    }
}
