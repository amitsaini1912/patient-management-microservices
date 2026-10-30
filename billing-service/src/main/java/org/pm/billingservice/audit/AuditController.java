package org.pm.billingservice.audit;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal ops endpoints (no api-gateway route, like /reconciliation):
 *   GET /audit/verify                                    walk the chain, report the first broken row (200 either way)
 *   GET /audit?entityType=payment&entityId=...&limit=50  one entity's history, oldest first
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditLog log;
    private final AuditVerifier verifier;

    public AuditController(AuditLog log, AuditVerifier verifier) {
        this.log = log;
        this.verifier = verifier;
    }

    @GetMapping("/verify")
    public AuditVerifier.Result verify() {
        return verifier.verify();
    }

    @GetMapping
    public List<AuditEntry> history(@RequestParam String entityType, @RequestParam String entityId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return log.forEntity(entityType, entityId, Math.min(Math.max(limit, 1), 500));
    }
}
