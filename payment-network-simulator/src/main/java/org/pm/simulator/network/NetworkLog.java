package org.pm.simulator.network;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The last messages that crossed the network, for GET /admin/network/messages and for tests.
 * Each entry is built from the message fields one by one: the PAN is only ever stored masked.
 */
@Component
public class NetworkLog {

    private static final Logger log = LoggerFactory.getLogger(NetworkLog.class);
    private static final int KEEP = 1_000;

    /** {@code mti} is an ISO MTI, or CLEARING for capture/refund presentments (batch files in real life). */
    public record Entry(Instant at, String mti, String stan, String rrn, String card, Long amountMinor, String currency,
                        Long billingAmountMinor, String billingCurrency, String responseCode, String note) {}

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Clock clock = Clock.systemUTC();

    void request(Iso8583.AuthorizationRequest m) {
        add(new Entry(clock.instant(), Iso8583.AuthorizationRequest.MTI, m.stan(), m.rrn(), Iso8583.maskPan(m.pan()),
                m.amountMinor(), m.currency(), m.billingAmountMinor(), m.billingCurrency(), null, null));
    }

    void response(Iso8583.AuthorizationResponse m, String note) {
        add(new Entry(clock.instant(), Iso8583.AuthorizationResponse.MTI, m.stan(), m.rrn(), null, null, null, null, null,
                m.responseCode().code(), note));
    }

    void reversal(Iso8583.ReversalRequest m) {
        add(new Entry(clock.instant(), Iso8583.ReversalRequest.MTI, m.stan(), m.originalRrn(), null, null, null, null,
                null, null, m.reason().name()));
    }

    void reversalResponse(Iso8583.ReversalResponse m) {
        add(new Entry(clock.instant(), Iso8583.ReversalResponse.MTI, m.stan(), m.originalRrn(), null, null, null, null,
                null, m.responseCode().code(), null));
    }

    void clearing(String rrn, String kind, long amountMinor, String currency, long billingAmountMinor, String billingCurrency) {
        add(new Entry(clock.instant(), "CLEARING", null, rrn, null, amountMinor, currency, billingAmountMinor,
                billingCurrency, null, kind));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    private synchronized void add(Entry entry) {
        log.info("{} rrn={} stan={} card={} amount={} {} billing={} {} code={} {}", entry.mti(), entry.rrn(), entry.stan(),
                entry.card(), entry.amountMinor(), entry.currency(), entry.billingAmountMinor(), entry.billingCurrency(),
                entry.responseCode(), entry.note() == null ? "" : entry.note());
        entries.addLast(entry);
        if (entries.size() > KEEP) {
            entries.removeFirst();
        }
    }
}
