package org.pm.billingservice.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** POST /payments/{id}/review: {"decision": "APPROVE" | "REJECT", "note": "why"} */
public record ReviewPaymentRequest(@NotNull Action decision, @Size(max = 500) String note) {

    public enum Action { APPROVE, REJECT }
}
