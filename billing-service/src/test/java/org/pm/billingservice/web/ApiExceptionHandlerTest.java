package org.pm.billingservice.web;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.pm.billingservice.payment.PaymentStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract for state errors. Payment endpoints arrive on Day 4a; a tiny fake controller throws for now. */
class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void illegalPaymentStateIs409ProblemJson() throws Exception {
        mvc.perform(post("/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Illegal payment state"))
                .andExpect(jsonPath("$.currentStatus").value("VOIDED"))
                .andExpect(jsonPath("$.requestedStatus").value("CAPTURED"));
    }

    @Test
    void optimisticLockFailureIs409() throws Exception {
        mvc.perform(post("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent modification"));
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/illegal-state")
        void illegalState() {
            throw new IllegalPaymentStateException(UUID.randomUUID(), PaymentStatus.VOIDED, PaymentStatus.CAPTURED);
        }

        @PostMapping("/stale")
        void stale() {
            throw new ObjectOptimisticLockingFailureException("Payment", UUID.randomUUID());
        }
    }
}
