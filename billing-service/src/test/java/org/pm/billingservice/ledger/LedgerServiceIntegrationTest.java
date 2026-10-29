package org.pm.billingservice.ledger;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Not @Transactional: every post really commits, so the deferred DB balance check really runs.
// The ledger is append-only, so tests don't clean up; each one uses a fresh patient receivable.
@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class LedgerServiceIntegrationTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private BillingAccountService billingAccountService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Test
    void systemAccountsAreSeededForInr() {
        for (AccountPurpose purpose : AccountPurpose.values()) {
            if (purpose == AccountPurpose.PATIENT_RECEIVABLE) {
                continue; // per patient, not a system account
            }
            assertThat(ledgerService.systemAccount(purpose, INR).getType()).isEqualTo(purpose.type());
        }
    }

    @Test
    void chargeRaisesReceivableAndRevenue() {
        LedgerAccount receivable = newPatientReceivable();
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR);
        Money revenueBefore = ledgerService.balanceOf(revenue);

        ledgerService.post(JournalEntry.draft("consultation fee")
                .debit(receivable, inr(500_00))
                .credit(revenue, inr(500_00)));

        // both are +500 because each is measured on its own normal side
        assertThat(ledgerService.balanceOf(receivable)).isEqualTo(inr(500_00));
        assertThat(ledgerService.balanceOf(revenue)).isEqualTo(revenueBefore.plus(inr(500_00)));
    }

    @Test
    void reversalBringsBalanceBackToZero() {
        LedgerAccount receivable = newPatientReceivable();
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR);
        JournalEntry charge = ledgerService.post(JournalEntry.draft("lab test")
                .debit(receivable, inr(1_250_00))
                .credit(revenue, inr(1_250_00)));

        JournalEntry reversal = ledgerService.reverse(charge.getId(), "lab test cancelled");

        assertThat(ledgerService.balanceOf(receivable)).isEqualTo(Money.zero(INR));
        assertThat(journalEntryRepository.findById(reversal.getId()).orElseThrow().getReversalOf())
                .isEqualTo(charge.getId());
        // the original is still there, unchanged: history shows the mistake AND the fix
        assertThat(journalEntryRepository.existsById(charge.getId())).isTrue();
    }

    @Test
    void entryCanBeReversedOnlyOnce() {
        LedgerAccount receivable = newPatientReceivable();
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR);
        JournalEntry charge = ledgerService.post(JournalEntry.draft("x-ray")
                .debit(receivable, inr(800_00))
                .credit(revenue, inr(800_00)));
        ledgerService.reverse(charge.getId(), "first undo");

        assertThatThrownBy(() -> ledgerService.reverse(charge.getId(), "second undo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reversed");
        assertThat(ledgerService.balanceOf(receivable)).isEqualTo(Money.zero(INR)); // not -800
    }

    @Test
    void unbalancedEntryNeverReachesTheDatabase() {
        LedgerAccount receivable = newPatientReceivable();
        LedgerAccount revenue = ledgerService.systemAccount(AccountPurpose.HOSPITAL_REVENUE, INR);
        JournalEntry draft = JournalEntry.draft("typo")
                .debit(receivable, inr(500_00))
                .credit(revenue, inr(50_00));

        assertThatThrownBy(() -> ledgerService.post(draft)).isInstanceOf(UnbalancedEntryException.class);

        assertThat(journalEntryRepository.existsById(draft.getId())).isFalse();
        assertThat(ledgerService.balanceOf(receivable)).isEqualTo(Money.zero(INR));
    }

    private LedgerAccount newPatientReceivable() {
        UUID billingAccountId = billingAccountService
                .getOrCreateAccount(UUID.randomUUID(), "Test Patient", "patient@test.com").getId();
        return ledgerService.patientReceivable(billingAccountId);
    }

    private static Money inr(long paise) {
        return new Money(paise, INR);
    }
}
