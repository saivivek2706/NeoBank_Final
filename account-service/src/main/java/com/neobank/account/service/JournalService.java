package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.GlAccount;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.Posting;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.domain.SystemAccount;
import com.neobank.account.repository.JournalEntryRepository;
import com.neobank.account.repository.PostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The double-entry posting engine. Every method writes ONE balanced journal (debits == credits) inside the
 * caller's transaction - the propagation is MANDATORY on purpose, so a journal can never be committed apart from
 * the balance change it describes.
 *
 * How the money flows (customer accounts are liabilities of the bank, so CREDIT raises a customer's balance):
 *
 *   opening deposit      Dr EXTERNAL_FUNDING          Cr customer
 *   transfer, debit leg  Dr sender (amount + fee)     Cr SETTLEMENT (amount)   Cr FEE_INCOME (fee)
 *   transfer, credit leg Dr SETTLEMENT                Cr receiver
 *   transfer reversal    the exact mirror of the debit leg (refunds the fee too)
 *   hold capture         Dr customer                  Cr EXTERNAL_FUNDING      (money paid out of the bank)
 *
 * SETTLEMENT is credited by the debit leg and debited by the credit leg, so it nets to zero when no transfer is in
 * flight - a lingering balance is a stuck transfer (see LedgerAuditService).
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JournalService {

    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final GlAccountService glAccountService;

    /** One side of a journal against either a customer account or a GL account. */
    public record Line(UUID customerAccountId, UUID glAccountId, PostingDirection direction, BigDecimal amount) {
        static Line customer(UUID accountId, PostingDirection direction, BigDecimal amount) {
            return new Line(accountId, null, direction, amount);
        }

        static Line gl(UUID glAccountId, PostingDirection direction, BigDecimal amount) {
            return new Line(null, glAccountId, direction, amount);
        }
    }

    /** What a reversal gave back to the sender: the transfer amount and the fee, separately (for statement lines). */
    public record ReversalResult(JournalEntry journal, BigDecimal principal, BigDecimal fee) {
        public BigDecimal total() {
            return principal.add(fee);
        }
    }

    public JournalEntry postOpeningDeposit(Account account, UUID transactionId, BigDecimal amount, String fundingReference) {
        GlAccount cash = glAccountService.system(SystemAccount.EXTERNAL_FUNDING, account.getCurrency());
        String reference = fundingReference == null || fundingReference.isBlank()
                ? "NB-DEP-" + transactionId : fundingReference.trim();
        return post(transactionId, JournalType.OPENING_DEPOSIT, account.getCurrency(), "Opening deposit", reference, null,
                List.of(Line.gl(cash.getId(), PostingDirection.DEBIT, amount),
                        Line.customer(account.getId(), PostingDirection.CREDIT, amount)));
    }

    public JournalEntry postTransferDebit(Account sender, UUID transactionId, BigDecimal amount, BigDecimal fee) {
        GlAccount settlement = glAccountService.system(SystemAccount.SETTLEMENT, sender.getCurrency());
        List<Line> lines = new ArrayList<>();
        lines.add(Line.customer(sender.getId(), PostingDirection.DEBIT, amount.add(fee)));
        lines.add(Line.gl(settlement.getId(), PostingDirection.CREDIT, amount));
        if (fee.signum() > 0) {
            GlAccount feeIncome = glAccountService.system(SystemAccount.FEE_INCOME, sender.getCurrency());
            lines.add(Line.gl(feeIncome.getId(), PostingDirection.CREDIT, fee));
        }
        return post(transactionId, JournalType.TRANSFER_DEBIT, sender.getCurrency(), "Transfer debit", null, null, lines);
    }

    public JournalEntry postTransferCredit(Account receiver, UUID transactionId, BigDecimal amount) {
        GlAccount settlement = glAccountService.system(SystemAccount.SETTLEMENT, receiver.getCurrency());
        return post(transactionId, JournalType.TRANSFER_CREDIT, receiver.getCurrency(), "Transfer credit", null, null,
                List.of(Line.gl(settlement.getId(), PostingDirection.DEBIT, amount),
                        Line.customer(receiver.getId(), PostingDirection.CREDIT, amount)));
    }

    /**
     * Compensation for a failed credit: posts the exact mirror of the original debit journal, so the sender gets
     * back the amount AND the fee. If the debit pre-dates the journal (a transfer that was in flight when the
     * double-entry migration ran) the fallback reverses `legacyAmount` against LEGACY_CLEARING instead.
     */
    public ReversalResult postTransferReversal(Account sender, UUID transactionId, BigDecimal legacyAmount) {
        Optional<JournalEntry> original = journalEntryRepository
                .findByTransactionIdAndJournalType(transactionId, JournalType.TRANSFER_DEBIT);

        if (original.isEmpty()) {
            GlAccount legacy = glAccountService.system(SystemAccount.LEGACY_CLEARING, sender.getCurrency());
            JournalEntry journal = post(transactionId, JournalType.TRANSFER_REVERSAL, sender.getCurrency(),
                    "Reversal of failed transfer (debit pre-dates the journal)", null, null,
                    List.of(Line.gl(legacy.getId(), PostingDirection.DEBIT, legacyAmount),
                            Line.customer(sender.getId(), PostingDirection.CREDIT, legacyAmount)));
            return new ReversalResult(journal, legacyAmount, BigDecimal.ZERO);
        }

        List<Posting> originalPostings = postingRepository.findByJournalId(original.get().getId());
        List<Line> mirror = new ArrayList<>();
        BigDecimal customerTotal = BigDecimal.ZERO;
        for (Posting p : originalPostings) {
            if (p.getCustomerAccountId() != null) {
                if (!p.getCustomerAccountId().equals(sender.getId())) {
                    throw new IllegalStateException("Reversal account does not match the original debit for transfer " + transactionId);
                }
                customerTotal = customerTotal.add(p.getAmount());
                mirror.add(Line.customer(p.getCustomerAccountId(), p.getDirection().opposite(), p.getAmount()));
            } else {
                mirror.add(Line.gl(p.getGlAccountId(), p.getDirection().opposite(), p.getAmount()));
            }
        }
        GlAccount settlement = glAccountService.system(SystemAccount.SETTLEMENT, sender.getCurrency());
        BigDecimal principal = originalPostings.stream()
                .filter(p -> settlement.getId().equals(p.getGlAccountId()))
                .map(Posting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        JournalEntry journal = post(transactionId, JournalType.TRANSFER_REVERSAL, sender.getCurrency(),
                "Reversal of failed transfer", null, original.get().getId(), mirror);
        return new ReversalResult(journal, principal, customerTotal.subtract(principal));
    }

    public JournalEntry postHoldCapture(Account account, UUID holdId, BigDecimal amount, String reference) {
        GlAccount cash = glAccountService.system(SystemAccount.EXTERNAL_FUNDING, account.getCurrency());
        return post(holdId, JournalType.HOLD_CAPTURE, account.getCurrency(), "Captured authorization hold",
                reference, null,
                List.of(Line.customer(account.getId(), PostingDirection.DEBIT, amount),
                        Line.gl(cash.getId(), PostingDirection.CREDIT, amount)));
    }

    private JournalEntry post(UUID transactionId, JournalType type, String currency, String description,
                              String externalReference, UUID reversesJournalId, List<Line> lines) {
        requireBalanced(lines);
        JournalEntry journal = journalEntryRepository.save(
                JournalEntry.create(transactionId, type, currency, description, externalReference, reversesJournalId));
        List<Posting> postings = new ArrayList<>(lines.size());
        for (Line line : lines) {
            postings.add(line.customerAccountId() != null
                    ? Posting.customer(journal.getId(), line.customerAccountId(), line.direction(), line.amount(), currency)
                    : Posting.gl(journal.getId(), line.glAccountId(), line.direction(), line.amount(), currency));
        }
        postingRepository.saveAll(postings);
        return journal;
    }

    /** Application-level twin of the database trigger: refuses to even attempt an unbalanced journal. */
    static void requireBalanced(List<Line> lines) {
        if (lines.size() < 2) {
            throw new IllegalStateException("A journal needs at least two postings");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new IllegalStateException("Posting amounts must be positive");
            }
            if (line.direction() == PostingDirection.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException("Unbalanced journal: debits=" + debits + " credits=" + credits);
        }
    }
}
