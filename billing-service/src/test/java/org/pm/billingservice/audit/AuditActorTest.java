package org.pm.billingservice.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActorTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void noRequestIsSystem() {
        assertThat(AuditActor.current()).isEqualTo("system");
    }

    @Test
    void gatewayUserWebhookAndDirectCalls() {
        assertThat(actorFor("POST", "/payments", "alice@test.com")).isEqualTo("user:alice@test.com");
        assertThat(actorFor("POST", "/webhooks/gateway", null)).isEqualTo("acquirer");
        assertThat(actorFor("POST", "/payments", null)).isEqualTo("internal");
        assertThat(actorFor("POST", "/payments", "x".repeat(300))).hasSize(100); // fits the column
    }

    private static String actorFor(String method, String uri, String user) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (user != null) {
            request.addHeader("X-User-Id", user);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return AuditActor.current();
    }
}
