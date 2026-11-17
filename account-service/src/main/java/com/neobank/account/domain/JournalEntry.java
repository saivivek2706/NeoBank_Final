package com.neobank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One business event in the books, made of two or more {@link Posting}s whose debits equal their credits (enforced
 * by a deferred database trigger, see V4). Append-only: a mistake is corrected by posting a reversing journal that
 * points back at the original via {@code reversesJournalId}.
 */
@Entity
@Table(name = "journal_entries")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalType journalType;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Bank reference for money entering/leaving the bank - what an external statement is matched against. */
    @Column(length = 100)
    private String externalReference;

    private UUID reversesJournalId;

    @Column(nullable = false)
    private Instant createdAt;

    public static JournalEntry create(UUID transactionId, JournalType type, String currency, String description,
                                      String externalReference, UUID reversesJournalId) {
        JournalEntry journal = new JournalEntry();
        journal.setTransactionId(transactionId);
        journal.setJournalType(type);
        journal.setCurrency(currency);
        journal.setDescription(description);
        journal.setExternalReference(externalReference);
        journal.setReversesJournalId(reversesJournalId);
        journal.setCreatedAt(Instant.now());
        return journal;
    }
}
