package org.pm.billingservice.reconciliation;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Goes with FakePaymentProcessor (tests): the acquirer's report is whatever a test puts in. */
@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "fake")
public class FakeSettlementSource implements SettlementSource {

    private final List<SettlementRow> rows = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @Override
    public List<SettlementRow> settlementFor(LocalDate date) {
        if (failure != null) {
            throw failure;
        }
        return rows.stream().filter(r -> r.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().equals(date)).toList();
    }

    public void add(SettlementRow row) {
        rows.add(row);
    }

    /** For tests: make the next fetches fail (acquirer down). null = work again. */
    public void failWith(RuntimeException e) {
        this.failure = e;
    }
}
