package org.pm.billingservice.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.pm.billingservice.web.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops endpoints, ADMIN only (ADR 0031/0033), through the gateway at /api/audit/**:
 *   GET /audit/verify                                    walk the chain and check it against the latest anchor
 *                                                        published to Kafka; report the first broken row (200 either way)
 *   GET /audit?entityType=payment&entityId=...&limit=50  one entity's history, oldest first
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditLog log;
    private final AuditVerifier verifier;
    private final AuditAnchors anchors;

    public AuditController(AuditLog log, AuditVerifier verifier, AuditAnchors anchors) {
        this.log = log;
        this.verifier = verifier;
        this.anchors = anchors;
    }

    @GetMapping("/verify")
    public AuditVerifier.Result verify(HttpServletRequest http) {
        Roles.requireAdmin(http, "verifying the audit log");
        AuditAnchors.Anchor anchor;
        try {
            anchor = anchors.latest().orElse(null);
        } catch (RuntimeException e) {
            // the chain is still checked; the answer says the end of it couldn't be (cut-off tail undetectable now)
            return verifier.verify().withAnchor(null, "anchor not checked: Kafka unreadable (" + e.getMessage() + ")");
        }
        AuditVerifier.Result result = verifier.verify(anchor);
        return anchor == null ? result.withAnchor(null, "no anchor published yet") : result;
    }

    @GetMapping
    public List<AuditEntry> history(@RequestParam String entityType, @RequestParam String entityId,
                                    @RequestParam(defaultValue = "50") int limit, HttpServletRequest http) {
        Roles.requireAdmin(http, "reading the audit log");
        return log.forEntity(entityType, entityId, Math.min(Math.max(limit, 1), 500));
    }
}
