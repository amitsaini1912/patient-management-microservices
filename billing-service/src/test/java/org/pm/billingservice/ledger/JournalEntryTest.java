package org.pm.billingservice.ledger;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.money.Money;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The double-entry rules in code. No database: the DB copy of the rules is tested in LedgerDatabaseRulesIntegrationTest. */
class JournalEntryTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    // two real receivables are enough here; which purpose an account has doesn't matter for balancing
    private final LedgerAccount inrA = LedgerAccount.patientReceivable(UUID.randomUUID(), INR);
    private final LedgerAccount inrB = LedgerAccount.patientReceivable(UUID.randomUUID(), INR);
    private final LedgerAccount usdA = LedgerAccount.patientReceivable(UUID.randomUUID(), USD);
    private final LedgerAccount usdB = LedgerAccount.patientReceivable(UUID.randomUUID(), USD);

    @Test
    void balancedEntryPosts() {
        JournalEntry entry = JournalEntry.draft("charge")
                .debit(inrA, inr(500_00))
                .credit(inrB, inr(500_00));

        entry.post();

        assertThat(entry.isPosted()).isTrue();
        assertThat(entry.getLines()).extracting(EntryLine::getLineNo).containsExactly(1, 2);
    }

    @Test
    void oneDebitCanBeSplitOverSeveralCredits() {
        JournalEntry entry = JournalEntry.draft("split")
                .debit(inrA, inr(100_00))
                .credit(inrB, inr(70_00))
                .credit(inrB, inr(30_00));

        entry.post();

        assertThat(entry.isPosted()).isTrue();
    }

    @Test
    void unbalancedEntryIsRefused() {
        JournalEntry entry = JournalEntry.draft("off by one paisa")
                .debit(inrA, inr(500_00))
                .credit(inrB, inr(499_99));

        assertThatThrownBy(entry::post)
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("unbalanced in INR");
        assertThat(entry.isPosted()).isFalse();
    }

    @Test
    void singleLineIsRefused() {
        JournalEntry entry = JournalEntry.draft("one-sided").debit(inrA, inr(1));

        assertThatThrownBy(entry::post)
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("at least 2 lines");
    }

    @Test
    void sameNumberInDifferentCurrenciesDoesNotBalance() {
        // 100 INR debit vs 100 USD credit: the numbers match, the money doesn't
        JournalEntry entry = JournalEntry.draft("mixed")
                .debit(inrA, inr(100))
                .credit(usdA, Money.ofMinor(100, "USD"));

        assertThatThrownBy(entry::post).isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void multiCurrencyEntryPostsWhenEachCurrencyBalances() {
        JournalEntry entry = JournalEntry.draft("two currencies")
                .debit(inrA, inr(100)).credit(inrB, inr(100))
                .debit(usdA, Money.ofMinor(5, "USD")).credit(usdB, Money.ofMinor(5, "USD"));

        entry.post();

        assertThat(entry.isPosted()).isTrue();
    }

    @Test
    void lineCurrencyMustMatchAccountCurrency() {
        assertThatThrownBy(() -> JournalEntry.draft("wrong currency").debit(inrA, Money.ofMinor(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is in INR");
    }

    @Test
    void zeroAndNegativeAmountsAreRefused() {
        JournalEntry entry = JournalEntry.draft("bad amounts");

        assertThatThrownBy(() -> entry.debit(inrA, inr(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.credit(inrA, inr(-5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postedEntryCannotGetMoreLines() {
        JournalEntry entry = JournalEntry.draft("charge").debit(inrA, inr(10)).credit(inrB, inr(10));
        entry.post();

        assertThatThrownBy(() -> entry.debit(inrA, inr(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(entry::post).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversalSwapsEveryLineAndPointsAtOriginal() {
        JournalEntry original = JournalEntry.draft("charge").debit(inrA, inr(500_00)).credit(inrB, inr(500_00));
        original.post();

        JournalEntry reversal = JournalEntry.reversalOf(original, "charged by mistake");

        assertThat(reversal.getReversalOf()).isEqualTo(original.getId());
        assertThat(reversal.getLines()).extracting(EntryLine::getDirection)
                .containsExactly(Direction.CREDIT, Direction.DEBIT);
        assertThat(reversal.getLines()).extracting(EntryLine::getAccount).containsExactly(inrA, inrB);
        assertThat(reversal.getLines()).extracting(EntryLine::getAmount).containsOnly(inr(500_00));
    }

    @Test
    void draftCannotBeReversed() {
        JournalEntry draft = JournalEntry.draft("not posted").debit(inrA, inr(1)).credit(inrB, inr(1));

        assertThatThrownBy(() -> JournalEntry.reversalOf(draft, "undo")).isInstanceOf(IllegalStateException.class);
    }

    private static Money inr(long paise) {
        return new Money(paise, INR);
    }
}
