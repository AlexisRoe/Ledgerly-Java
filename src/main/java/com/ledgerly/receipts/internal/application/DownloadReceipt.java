package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.error.NotFound;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.storage.ObjectStorage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gives a short-lived, signed link to an accepted receipt; the file is served by the object store, not by us. */
@Service
public class DownloadReceipt {

    /** Five minutes at most: long enough to click, short enough that a leaked link soon dies. */
    static final Duration LINK_TTL = Duration.ofMinutes(5);

    /** {@code url} to fetch the file; valid until {@code expiresAt}. */
    public record Link(URI url, Instant expiresAt) {}

    private final ExpensesFacade expenses;
    private final ReceiptRepository receipts;
    private final ObjectStorage storage;
    private final Clock clock;

    DownloadReceipt(ExpensesFacade expenses, ReceiptRepository receipts, ObjectStorage storage, Clock clock) {
        this.expenses = expenses;
        this.receipts = receipts;
        this.storage = storage;
        this.clock = clock;
    }

    /** Only ACCEPTED receipts. Pending and quarantined ones are a 404, as if they did not exist. */
    @Transactional(readOnly = true)
    public Link execute(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId caller) {
        expenses.requireCanView(teamId, expenseId, caller);
        var receipt = receipts.findByIdAndTeamIdAndExpenseId(receiptId, teamId.value(), expenseId.value())
                .filter(r -> r.isDownloadable())
                .orElseThrow(NotFound::new);
        String name = FileNames.sanitize(receipt.originalName(), receipt.detectedType());
        URI url = storage.presignGet(receipt.storageKey(), name, receipt.detectedType(), LINK_TTL);
        return new Link(url, clock.instant().plus(LINK_TTL));
    }
}
