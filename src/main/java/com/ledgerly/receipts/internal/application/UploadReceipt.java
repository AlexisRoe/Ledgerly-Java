package com.ledgerly.receipts.internal.application;

import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.receipts.events.ReceiptEvent;
import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.infrastructure.ReceiptRepository;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.Conflict;
import com.ledgerly.shared.error.ErrorCatalogue;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.error.UnsupportedMedia;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.io.CappedInputStream;
import com.ledgerly.shared.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The five steps of a receipt upload: stream to a temp file while hashing and capping the size; detect the real
 * type from the bytes; store under a random key; record it as PENDING_SCAN; scan asynchronously.
 */
@Service
public class UploadReceipt {

    static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    /** Magic-byte detection only: the file name and the declared content type are never consulted. */
    private static final Tika TIKA = new Tika();

    public record Command(
            TeamId teamId, ExpenseId expenseId, UserId caller, String originalName, InputStream content) {}

    private final ExpensesFacade expenses;
    private final ReceiptRepository receipts;
    private final ObjectStorage storage;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final LedgerlyLimits limits;
    private final Clock clock;

    UploadReceipt(
            ExpensesFacade expenses,
            ReceiptRepository receipts,
            ObjectStorage storage,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            LedgerlyLimits limits,
            Clock clock) {
        this.expenses = expenses;
        this.receipts = receipts;
        this.storage = storage;
        this.events = events;
        this.tx = tx;
        this.limits = limits;
        this.clock = clock;
    }

    public ReceiptView execute(Command c) {
        // Permission first: nothing is read from the client before we know the caller may upload here.
        expenses.requireCanChangeReceipts(c.teamId(), c.expenseId(), c.caller());

        Path temp = null;
        try {
            temp = Files.createTempFile("receipt-", ".upload");
            Staged staged = stage(c.content(), temp);
            if (staged.size() == 0) {
                throw new InvalidRequest("file", "The file is empty");
            }
            if (!ALLOWED_TYPES.contains(staged.type())) {
                throw new UnsupportedMedia("Only PDF, JPEG and PNG files are accepted");
            }
            UUID expense = c.expenseId().value();
            UUID team = c.teamId().value();
            if (receipts.existsByExpenseIdAndSha256(expense, staged.sha256())) {
                throw new Conflict(ErrorCatalogue.RECEIPT_DUPLICATE, "This file is already attached to the expense");
            }
            boolean possibleDuplicate = receipts.existsByTeamIdAndSha256AndExpenseIdNot(team, staged.sha256(), expense);

            // A random key: the client's file name never reaches the object store as a path.
            String key = "receipts/" + UUID.randomUUID();
            storage.put(key, temp, staged.type());
            try {
                return tx.execute(status -> {
                    Receipt receipt = receipts.saveAndFlush(new Receipt(
                            team,
                            expense,
                            key,
                            displayName(c.originalName()),
                            staged.type(),
                            staged.size(),
                            staged.sha256(),
                            possibleDuplicate,
                            c.caller().value(),
                            clock.instant()));
                    events.publishEvent(new ReceiptUploaded(receipt.id()));
                    events.publishEvent(new ReceiptEvent.Uploaded(
                            c.teamId(), c.expenseId(), receipt.id(), c.caller(), clock.instant()));
                    return ReceiptView.of(receipt);
                });
            } catch (DataIntegrityViolationException e) {
                // Lost a race with an identical upload: the unique index decided.
                storage.delete(key);
                throw new Conflict(ErrorCatalogue.RECEIPT_DUPLICATE, "This file is already attached to the expense");
            } catch (RuntimeException e) {
                storage.delete(key);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // a leftover temp file is cleaned by the OS; nothing sensitive is lost
                }
            }
        }
    }

    private record Staged(long size, String sha256, String type) {}

    private Staged stage(InputStream content, Path temp) throws IOException {
        MessageDigest digest = newDigest();
        long limit = limits.maxUploadSize().toBytes();
        try (InputStream in = new DigestInputStream(new CappedInputStream(content, limit), digest)) {
            long size = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            String type;
            try (InputStream sniff = Files.newInputStream(temp)) {
                type = TIKA.detect(sniff);
            }
            return new Staged(size, HexFormat.of().formatHex(digest.digest()), type);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The name is kept as metadata only. Control characters are dropped and the length capped. */
    private static String displayName(String original) {
        String name = original.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "receipt";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
