package org.pm.billingservice.risk;

import java.util.List;
import java.util.Objects;

/**
 * The risk decision for one payment. {@code score}, {@code modelVersion}, {@code rulesVersion} only come from the
 * engine (null for fallback, or when the engine's model could not score). {@code fallbackReason}: why the engine's
 * answer wasn't used (null for ENGINE).
 */
public record RiskVerdict(RiskDecision decision, RiskSource source, List<String> reasonCodes, Double score,
                          String modelVersion, String rulesVersion, String fallbackReason) {

    public RiskVerdict {
        Objects.requireNonNull(decision, "decision is required");
        Objects.requireNonNull(source, "source is required");
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    static RiskVerdict fallback(RiskDecision decision, List<String> reasonCodes, String fallbackReason) {
        return new RiskVerdict(decision, RiskSource.FALLBACK, reasonCodes, null, null, null, fallbackReason);
    }
}
