package com.neobank.account.repository;

import com.neobank.account.domain.GlAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GlAccountRepository extends JpaRepository<GlAccount, UUID> {

    Optional<GlAccount> findByCodeAndCurrency(String code, String currency);

    /**
     * Race-free "create if missing": two replicas posting their first journal for a currency at the same moment
     * would otherwise both try to insert the same (code, currency) row, and the loser's unique-violation would
     * poison its transaction. ON CONFLICT DO NOTHING makes the insert a no-op for the loser.
     */
    @Modifying
    @Query(value = """
            INSERT INTO gl_accounts (id, code, name, account_type, currency, created_at)
            VALUES (:id, :code, :name, :type, :currency, now())
            ON CONFLICT (code, currency) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("code") String code, @Param("name") String name,
                       @Param("type") String type, @Param("currency") String currency);
}
