package org.pm.billingservice.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.audit.AuditAnchors.Published;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0033: the one tampering the HMAC chain alone can't see - the newest rows deleted AND audit_chain_head moved back,
 * leaving a valid, shorter chain - is caught against the head published to Kafka. Real Postgres + real Kafka.
 * Every tampering is undone in a finally block (the chain is shared with the other test classes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class AuditAnchorIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private AuditLog audit;
    @Autowired private AuditVerifier verifier;
    @Autowired private AuditAnchors anchors;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void cuttingOffTheEndAndMovingTheHeadBackIsInvisibleToTheChainButCaughtByTheAnchor() {
        append(3);
        assertThat(anchors.publish()).isEqualTo(Published.PUBLISHED);
        AuditAnchors.Anchor anchor = anchors.latest().orElseThrow();
        AuditLog.Head head = audit.head();
        assertThat(anchor.seq()).isEqualTo(head.lastSeq());
        AuditEntry last = row(head.lastSeq());
        AuditEntry before = row(head.lastSeq() - 1);

        jdbc.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only");
        try {
            // the DBA's attack: drop the newest row, rewind the head to the row before - a perfectly valid chain
            jdbc.update("DELETE FROM audit_log WHERE seq = ?", last.seq());
            jdbc.update("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE id = 1", before.seq(), before.hash());

            assertThat(verifier.verify().valid()).isTrue();                     // the gap ADR 0016 left open
            AuditVerifier.Result checked = verifier.verify(anchors.latest().orElseThrow());
            assertThat(checked.valid()).isFalse();
            assertThat(checked.firstBrokenSeq()).isEqualTo(last.seq());
            assertThat(checked.reason()).contains("moved back");

            // and the scheduled publisher won't overwrite the evidence with the rewound head
            assertThat(anchors.publish()).isEqualTo(Published.REFUSED_HEAD_BEHIND);
            assertThat(anchors.latest().orElseThrow().seq()).isEqualTo(last.seq());
        } finally {
            reinsert(last);
            jdbc.update("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE id = 1", last.seq(), last.hash());
            jdbc.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only");
        }
        assertThat(verifier.verify(anchors.latest().orElseThrow()).valid()).isTrue(); // put back: valid again
    }

    @Test
    void aHeadWithADifferentHashAtThePublishedSeqIsRefusedAndNothingChangesWithoutNewRows() {
        append(1);
        anchors.publish();
        assertThat(anchors.publish()).isEqualTo(Published.UNCHANGED);            // no new rows: nothing to publish
        AuditLog.Head head = audit.head();
        try {
            jdbc.update("UPDATE audit_chain_head SET last_hash = ? WHERE id = 1", "f".repeat(64));
            assertThat(anchors.publish()).isEqualTo(Published.REFUSED_HASH_DIFFERS);
        } finally {
            jdbc.update("UPDATE audit_chain_head SET last_hash = ? WHERE id = 1", head.lastHash());
        }
        append(1);
        assertThat(anchors.publish()).isEqualTo(Published.PUBLISHED);           // moving forward is fine
        assertThat(anchors.latest().orElseThrow().seq()).isEqualTo(head.lastSeq() + 1);
    }

    @Test
    void theVerifyEndpointChecksTheAnchorAndIsAdminOnly() throws Exception {
        append(1);
        anchors.publish();

        HttpResponse<String> asAdmin = get("/audit/verify", "ADMIN");
        JsonNode result = objectMapper.readTree(asAdmin.body());
        assertThat(asAdmin.statusCode()).isEqualTo(200);
        assertThat(result.get("valid").asBoolean()).isTrue();
        assertThat(result.get("anchorSeq").asLong()).isEqualTo(audit.head().lastSeq());

        assertThat(get("/audit/verify", null).statusCode()).isEqualTo(403);
        assertThat(get("/audit/verify", "USER").statusCode()).isEqualTo(403);
        assertThat(get("/audit?entityType=test&entityId=x", "USER").statusCode()).isEqualTo(403);
    }

    // ---- helpers ----

    private void append(int n) {
        for (int i = 0; i < n; i++) {
            int index = i;
            tx.executeWithoutResult(status -> audit.append("test.anchor", "test", UUID.randomUUID().toString(), Map.of("i", index)));
        }
    }

    private AuditEntry row(long seq) {
        return audit.page(seq - 1, seq, 1).get(0);
    }

    private void reinsert(AuditEntry e) {
        jdbc.update("""
                        INSERT INTO audit_log (seq, occurred_at, actor, action, entity_type, entity_id, details, prev_hash, hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                e.seq(), Timestamp.from(e.occurredAt()), e.actor(), e.action(), e.entityType(), e.entityId(),
                e.details(), e.prevHash(), e.hash());
    }

    private HttpResponse<String> get(String path, String role) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (role != null) {
            request.header("X-User-Role", role);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
