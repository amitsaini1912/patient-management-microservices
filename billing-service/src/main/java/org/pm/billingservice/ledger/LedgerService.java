package org.pm.billingservice.ledger;

import java.util.Currency;
import java.util.UUID;
import org.pm.billingservice.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public LedgerService(LedgerAccountRepository ledgerAccountRepository,
                         JournalEntryRepository journalEntryRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Checks the entry in code, then saves it. The DB checks the balance again when the
     * transaction commits, so a caller's outer transaction is covered too.
     */
    @Transactional
    public JournalEntry post(JournalEntry draft) {
        draft.post();
        return journalEntryRepository.save(draft);
    }

    /**
     * Cancels a posted entry by posting its mirror image. The original is never changed:
     * the history shows both the mistake and the correction.
     */
    @Transactional
    public JournalEntry reverse(UUID entryId, String reason) {
        JournalEntry original = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("journal entry " + entryId + " not found"));
        // fast, friendly check; the UNIQUE(reversal_of) constraint is the real guard against two concurrent reversals
        if (journalEntryRepository.existsByReversalOf(entryId)) {
            throw new IllegalStateException("journal entry " + entryId + " is already reversed");
        }
        return post(JournalEntry.reversalOf(original, reason));
    }

    /**
     * Balance derived from the lines, positive when the account is on its normal side:
     * a receivable of ₹500 is +500, revenue of ₹500 is also +500.
     */
    @Transactional(readOnly = true)
    public Money balanceOf(LedgerAccount account) {
        long debitsMinusCredits = journalEntryRepository.debitsMinusCredits(account.getId(), Direction.DEBIT);
        long balance = account.getType().normalBalance() == Direction.DEBIT
                ? debitsMinusCredits
                : Math.negateExact(debitsMinusCredits);
        return new Money(balance, account.getCurrency());
    }

    @Transactional(readOnly = true)
    public LedgerAccount systemAccount(AccountPurpose purpose, Currency currency) {
        return ledgerAccountRepository.findByPurposeAndCurrencyAndBillingAccountIdIsNull(purpose, currency)
                .orElseThrow(() -> new IllegalStateException("no " + purpose + " account for " + currency));
    }

    @Transactional(readOnly = true)
    public LedgerAccount patientReceivable(UUID billingAccountId) {
        return ledgerAccountRepository.findByBillingAccountIdAndPurpose(billingAccountId, AccountPurpose.PATIENT_RECEIVABLE)
                .orElseThrow(() -> new IllegalStateException("no receivable for billing account " + billingAccountId));
    }
}
