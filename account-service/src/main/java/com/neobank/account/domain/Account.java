package com.neobank.account.domain;

import jakarta.persistence.*;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Core account entity. Balance mutations always go through the service layer's
 * debit()/credit() methods, never set directly, so invariants (non-negative
 * balance, frozen-account checks) can't be bypassed.
 *
 * @Version enables JPA optimistic locking: concurrent transfers touching the
 * same account will cause one to fail fast with OptimisticLockException rather
 * than silently corrupting the balance (the classic "lost update" bug in banking systems).
 */
@Entity
@Table(name = "accounts")
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType accountType;

    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public Account() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public String getAccountNumber() {
        return this.accountNumber;
    }

    public UUID getOwnerId() {
        return this.ownerId;
    }

    public String getOwnerName() {
        return this.ownerName;
    }

    public BigDecimal getBalance() {
        return this.balance;
    }

    public String getCurrency() {
        return this.currency;
    }

    public AccountStatus getStatus() {
        return this.status;
    }

    public AccountType getAccountType() {
        return this.accountType;
    }

    public Long getVersion() {
        return this.version;
    }

    private void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public static Account open(String accountNumber, UUID ownerId, String ownerName,
                                AccountType type, String currency, BigDecimal openingBalance) {
        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setOwnerId(ownerId);
        account.setOwnerName(ownerName);
        account.setAccountType(type);
        account.setCurrency(currency);
        account.setBalance(openingBalance);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        return account;
    }

    public void debit(BigDecimal amount) {
        requireActive();
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance on account " + accountNumber);
        }
        setBalance(balance.subtract(amount));
    }

    public void credit(BigDecimal amount) {
        requireActive();
        setBalance(balance.add(amount));
    }

    /** Compensating action used by the saga when a downstream credit fails after a debit succeeded. */
    public void reverseDebit(BigDecimal amount) {
        setBalance(balance.add(amount));
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account " + accountNumber + " is " + status + ", cannot process funds");
        }
    }

    public void freeze() {
        setStatus(AccountStatus.FROZEN);
    }

    public void unfreeze() {
        setStatus(AccountStatus.ACTIVE);
    }

    /**
     * "•••• 4821" - masks everything but the last 4 characters. Used for list/
     * summary views (accounts overview, notification previews); the full
     * number is only ever returned from a request for that ONE specific
     * account (see AccountController), matching how real banking apps only
     * show a full account number once you've explicitly opened it - not on
     * a shoulder-surfable list screen.
     */
    public String getMaskedAccountNumber() {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber;
        }
        int visibleFrom = accountNumber.length() - 4;
        return "••••" + accountNumber.substring(visibleFrom);
    }
}
