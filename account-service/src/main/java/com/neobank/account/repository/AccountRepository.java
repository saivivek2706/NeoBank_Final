package com.neobank.account.repository;

import com.neobank.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByOwnerId(UUID ownerId);

    /**
     * Pessimistic write lock for the saga consumers: two concurrent transfers
     * touching the same account will serialize here instead of racing on the
     * optimistic @Version check (which would throw and require a retry loop).
     * Held only for the duration of the single debit/credit operation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}
