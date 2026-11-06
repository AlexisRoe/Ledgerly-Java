package com.ledgerly.receipts.internal.infrastructure;

import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.domain.ScanStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Receipts table. Lookups carry the team id so another team's receipt cannot be reached. */
public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Always scoped by team and expense: a receipt id alone is never enough to reach a file. */
    Optional<Receipt> findByIdAndTeamIdAndExpenseId(UUID id, UUID teamId, UUID expenseId);

    /** An expense's receipts, oldest first. */
    List<Receipt> findByTeamIdAndExpenseIdOrderByCreatedAt(UUID teamId, UUID expenseId);

    /** Whether this exact file is already attached to the expense. */
    boolean existsByExpenseIdAndSha256(UUID expenseId, String sha256);

    /** Whether the same file is attached to another expense of the team (possible duplicate claim). */
    boolean existsByTeamIdAndSha256AndExpenseIdNot(UUID teamId, String sha256, UUID expenseId);

    /** How many of the expense's receipts have that scan status. */
    long countByExpenseIdAndScanStatus(UUID expenseId, ScanStatus scanStatus);

    /** All receipts of an expense, whatever their status (cleanup). */
    List<Receipt> findByExpenseId(UUID expenseId);
}
