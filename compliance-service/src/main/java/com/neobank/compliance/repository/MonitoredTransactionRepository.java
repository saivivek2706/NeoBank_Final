package com.neobank.compliance.repository;

import com.neobank.compliance.domain.MonitoredTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MonitoredTransactionRepository extends JpaRepository<MonitoredTransaction, UUID> {
    List<MonitoredTransaction> findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(UUID accountId, Instant since);
}
