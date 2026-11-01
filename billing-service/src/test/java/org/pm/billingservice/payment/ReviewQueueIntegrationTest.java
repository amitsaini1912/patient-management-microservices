package org.pm.billingservice.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0034: the manual-review queue. Risk is off in tests, so billing's fallback rules decide: >= INR 50,000 is HELD
 * (FB_HIGH_AMOUNT), smaller payments go through. Other test classes leave held payments too: assertions look at ours.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class ReviewQueueIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private PaymentService paymentService;
    @Autowired private BillingAccountService billingAccountService;
    @Autowired private MeterRegistry meters;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID billingAccountId;

    @BeforeEach
    void account() {
        billingAccountId = billingAccountService.getOrCreateAccount(UUID.randomUUID(), "Review Patient", "review@test.com").getId();
    }

    @Test
    void heldPaymentsAreListedOldestFirstWithTheirReasonsAndLeaveTheQueueWhenDecided() throws Exception {
        Payment first = pay("60000.00");
        Payment second = pay("75000.00");
        Payment normal = pay("1200.00");
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(normal.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        List<JsonNode> queue = queue();
        List<String> ids = queue.stream().map(p -> p.get("id").asText()).toList();
        assertThat(ids).contains(first.getId().toString(), second.getId().toString())
                .doesNotContain(normal.getId().toString());
        assertThat(ids.indexOf(first.getId().toString())).isLessThan(ids.indexOf(second.getId().toString())); // oldest first
        JsonNode mine = queue.get(ids.indexOf(first.getId().toString()));
        assertThat(mine.at("/risk/reasonCodes/0").asText()).isEqualTo("FB_HIGH_AMOUNT");   // what the reviewer needs
        assertThat(mine.get("amount").asText()).isEqualTo("60000.00");

        assertThat(meters.get("billing.payments.pending.review").gauge().value()).isGreaterThanOrEqualTo(2);
        assertThat(meters.get("billing.payments.pending.review.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);

        paymentService.review(first.getId(), Payment.ReviewDecision.REJECTED, "admin@test.com", "test");
        assertThat(queue().stream().map(p -> p.get("id").asText())).doesNotContain(first.getId().toString())
                .contains(second.getId().toString());
    }

    @Test
    void theQueueIsAdminOnly() throws Exception {
        assertThat(get("/payments/reviews", null).statusCode()).isEqualTo(403);
        assertThat(get("/payments/reviews", "USER").statusCode()).isEqualTo(403);
        assertThat(get("/payments/reviews?limit=1", "ADMIN").statusCode()).isEqualTo(200);
    }

    private Payment pay(String inr) {
        return paymentService.authorize(UUID.randomUUID().toString(), billingAccountId, Money.parse(inr, "INR"), "pm_card_visa");
    }

    private List<JsonNode> queue() throws Exception {
        HttpResponse<String> response = get("/payments/reviews?limit=200", "ADMIN");
        assertThat(response.statusCode()).isEqualTo(200);
        List<JsonNode> list = new ArrayList<>();
        objectMapper.readTree(response.body()).forEach(list::add);
        return list;
    }

    private HttpResponse<String> get(String path, String role) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (role != null) {
            request.header("X-User-Role", role);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
