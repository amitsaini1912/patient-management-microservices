package org.pm.billingservice.fx;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pm.billingservice.audit.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange rates from the fx_rate table. Rates are entered (seeded, POST /fx/rates); fetching them from a provider
 * is out of scope. The latest rate with as_of <= now wins; a newer row never changes a rate already locked on a payment.
 */
@Service
public class FxRateService {

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final Clock clock;
    private final Set<Currency> supported;

    public FxRateService(JdbcTemplate jdbc, AuditLog audit, Clock clock,
                         @Value("${billing.fx.supported-currencies:INR,USD,EUR}") Set<String> supported) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.supported = supported.stream().map(Currency::getInstance).collect(Collectors.toUnmodifiableSet());
    }

    public Set<Currency> supportedCurrencies() {
        return supported;
    }

    public void requireSupported(Currency currency) {
        if (!supported.contains(currency)) {
            throw new IllegalArgumentException("currency " + currency + " is not supported (supported: "
                    + supported.stream().map(Currency::getCurrencyCode).sorted().collect(Collectors.joining(", ")) + ")");
        }
    }

    /** The rate in force now. Same currency -> 1. Throws NoFxRateException if none is on file. */
    public FxRate current(Currency base, Currency quote) {
        if (base.equals(quote)) {
            return FxRate.identity(base);
        }
        return jdbc.query("""
                        SELECT id, rate, source, as_of FROM fx_rate
                        WHERE base_currency = ? AND quote_currency = ? AND as_of <= ?
                        ORDER BY as_of DESC, created_at DESC LIMIT 1""",
                (rs, i) -> new FxRate(rs.getObject("id", UUID.class), base, quote, rs.getBigDecimal("rate"),
                        rs.getString("source"), rs.getTimestamp("as_of").toInstant()),
                base.getCurrencyCode(), quote.getCurrencyCode(), Timestamp.from(clock.instant()))
                .stream().findFirst().orElseThrow(() -> new NoFxRateException(base, quote));
    }

    /** A rate typed in by a person moves money on every later payment: audited in the same transaction (ADR 0016). */
    @Transactional
    public FxRate record(Currency base, Currency quote, BigDecimal rate, String source, Instant asOf) {
        requireSupported(base);
        requireSupported(quote);
        if (base.equals(quote)) {
            throw new IllegalArgumentException("a rate needs two different currencies");
        }
        if (rate == null || rate.signum() <= 0 || rate.scale() > 8 || rate.precision() - rate.scale() > 10) {
            throw new IllegalArgumentException("rate must be positive, at most 10 digits before and 8 after the point");
        }
        FxRate fx = new FxRate(UUID.randomUUID(), base, quote, rate, source, asOf != null ? asOf : clock.instant());
        jdbc.update("INSERT INTO fx_rate (id, base_currency, quote_currency, rate, source, as_of) VALUES (?, ?, ?, ?, ?, ?)",
                fx.id(), base.getCurrencyCode(), quote.getCurrencyCode(), rate, source, Timestamp.from(fx.asOf()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("base", base.getCurrencyCode());
        details.put("quote", quote.getCurrencyCode());
        details.put("rate", rate.toPlainString());
        details.put("source", source);
        details.put("as_of", fx.asOf().toString());
        audit.append("fx.rate_recorded", "fx_rate", fx.id().toString(), details);
        return fx;
    }
}
