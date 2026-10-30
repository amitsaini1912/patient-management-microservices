package org.pm.billingservice.fx;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pm.billingservice.ledger.AccountPurpose;
import org.pm.billingservice.ledger.JournalEntry;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks the open foreign-currency position to today's rates (ADR 0012).
 *
 *   position_X   = credit balance of FX_CLEARING(X)          e.g. USD 50.00 the acquirer still owes us
 *   carried      = debit balance of FX_CLEARING(book)         what those positions are on our books for, in INR
 *   worth now    = sum of position_X converted at the latest X->INR rate
 *   delta        = worth now - carried  -> DR/CR FX_CLEARING(INR) against FX_GAIN_LOSS (unrealized gain/loss)
 *
 * After a run, carried = worth now, so running again with the same rates posts nothing.
 */
@Service
public class FxRevaluationService {

    private static final Logger log = LoggerFactory.getLogger(FxRevaluationService.class);
    private static final long REVALUATION_LOCK_ID = 0x666f7872L; // "fxr"

    private final JdbcTemplate jdbc;
    private final LedgerService ledgerService;
    private final FxRateService rates;
    private final Currency bookCurrency;

    public FxRevaluationService(JdbcTemplate jdbc, LedgerService ledgerService, FxRateService rates,
                                @Value("${billing.fx.book-currency:INR}") String bookCurrency) {
        this.jdbc = jdbc;
        this.ledgerService = ledgerService;
        this.rates = rates;
        this.bookCurrency = Currency.getInstance(bookCurrency);
    }

    public record Result(Map<String, Money> positions, Money carried, Money worthNow, Money delta) {}

    @Transactional
    public Result revalue() {
        // two revaluations at once would both see the same "carried" and post the delta twice
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, REVALUATION_LOCK_ID);

        // ONE statement = one snapshot: a capture committing between two queries can't make the sides disagree
        Map<Currency, Long> debitsMinusCredits = new LinkedHashMap<>();
        jdbc.query("""
                SELECT la.currency, SUM(CASE WHEN el.direction = 'DEBIT' THEN el.amount_minor ELSE -el.amount_minor END)
                FROM entry_line el JOIN ledger_account la ON la.id = el.ledger_account_id
                WHERE la.purpose = 'FX_CLEARING'
                GROUP BY la.currency ORDER BY la.currency""",
                rs -> { debitsMinusCredits.put(Currency.getInstance(rs.getString(1)), rs.getLong(2)); });

        Money carried = new Money(debitsMinusCredits.getOrDefault(bookCurrency, 0L), bookCurrency);
        Money worthNow = Money.zero(bookCurrency);
        Map<String, Money> positions = new LinkedHashMap<>();
        for (Map.Entry<Currency, Long> e : debitsMinusCredits.entrySet()) {
            if (e.getKey().equals(bookCurrency)) {
                continue;
            }
            Money position = new Money(-e.getValue(), e.getKey()); // credit balance = what we hold
            positions.put(e.getKey().getCurrencyCode(), position);
            worthNow = worthNow.plus(rates.current(e.getKey(), bookCurrency).convert(position));
        }
        Money delta = worthNow.minus(carried);
        if (!delta.isZero()) {
            LedgerAccount clearing = ledgerService.systemAccount(AccountPurpose.FX_CLEARING, bookCurrency);
            LedgerAccount gainLoss = ledgerService.systemAccount(AccountPurpose.FX_GAIN_LOSS, bookCurrency);
            JournalEntry entry = JournalEntry.draft("FX revaluation of open positions " + positions);
            ledgerService.post(delta.isPositive()
                    ? entry.debit(clearing, delta).credit(gainLoss, delta)                      // unrealized gain
                    : entry.debit(gainLoss, delta.negate()).credit(clearing, delta.negate()));  // unrealized loss
            log.info("FX revaluation: positions {}, carried {}, worth {}, posted {}", positions, carried, worthNow, delta);
        }
        return new Result(positions, carried, worthNow, delta);
    }
}
