package com.ledgerly.receipts.internal.infrastructure;

import com.ledgerly.receipts.internal.domain.Receipt;
import com.ledgerly.receipts.internal.domain.ScanStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    /** Always scoped by team and expense: a receipt id alone is never enough to reach a file. */
    Optional<Receipt> findByIdAndTeamIdAndExpenseId(UUID id, UUID teamId, UUID expenseId);

    List<Receipt> findByTeamIdAndExpenseIdOrderByCreatedAt(UUID teamId, UUID expenseId);

    boolean existsByExpenseIdAndSha256(UUID expenseId, String sha256);

    boolean existsByTeamIdAndSha256AndExpenseIdNot(UUID teamId, String sha256, UUID expenseId);

    long countByExpenseIdAndScanStatus(UUID expenseId, ScanStatus scanStatus);

    List<Receipt> findByExpenseId(UUID expenseId);
}
