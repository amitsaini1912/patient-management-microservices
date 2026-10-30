package org.pm.simulator.settlement;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 *   GET   /v1/settlements/{date}       the settlement report for one UTC day (JSON)
 *   PATCH /admin/settlements/{id}      test knob: change a row's amount/currency to fake an acquirer-side error
 */
@RestController
public class SettlementController {

    private final SettlementBook book;

    public SettlementController(SettlementBook book) {
        this.book = book;
    }

    public record SettlementReport(LocalDate date, String cutoff, List<SettlementRecord> rows) {}

    public record Adjustment(Long amountMinor, String currency) {}

    @GetMapping("/v1/settlements/{date}")
    public SettlementReport report(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new SettlementReport(date, "00:00Z", book.forDate(date));
    }

    @PatchMapping("/admin/settlements/{id}")
    public ResponseEntity<SettlementRecord> adjust(@PathVariable String id, @RequestBody Adjustment adjustment) {
        return book.adjust(id, adjustment.amountMinor(), adjustment.currency())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
