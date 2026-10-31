package org.pm.billingservice.web;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.Chargeback;
import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentNotFoundException;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.PaymentStatus;
import org.pm.billingservice.payment.Refund;
import org.pm.billingservice.payment.UnknownBillingAccountException;
import org.pm.billingservice.processor.PaymentProcessorException;
import org.pm.billingservice.risk.RiskContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP layer only: JSON in/out, validation, status codes. PaymentService is a mock. */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String KEY = "test-key-" + UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void authorizeReturns201WithLocationAndDecimalAmounts() throws Exception {
        Payment payment = authorizedPayment(1_000_50);
        when(paymentService.authorize(eq(KEY), eq(ACCOUNT_ID), eq(inr(1_000_50)), eq("pm_card_visa"), any())).thenReturn(payment);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"billingAccountId":"%s","amount":"1000.50","currency":"INR","paymentMethodToken":"pm_card_visa"}
                        """.formatted(ACCOUNT_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/payments/" + payment.getId()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value("1000.50"))   // a string, not a JSON number
                .andExpect(jsonPath("$.capturedAmount").value("0.00"))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void declinedCardIsStill201WithFailedStatus() throws Exception {
        Payment payment = Payment.create(ACCOUNT_ID, inr(500_00));
        payment.fail("card_declined");
        when(paymentService.authorize(any(), any(), any(), any(), any())).thenReturn(payment);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));
    }

    @Test
    void issuerUnavailableShowsTheCodeAndThatANewAttemptMayWork() throws Exception {
        Payment payment = Payment.create(ACCOUNT_ID, inr(500_00));
        payment.fail("issuer_unavailable", "91", true);
        when(paymentService.authorize(any(), any(), any(), any(), any())).thenReturn(payment);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.networkResponseCode").value("91"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void missingFieldsAre400ProblemJson() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));

        verify(paymentService, never()).authorize(any(), any(), any(), any(), any());
    }

    @Test
    void negativeOrNonDecimalAmountIs400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("-5.00")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("1e3")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void moreDecimalsThanCurrencyAllowsIs400() throws Exception {
        // passes the regex, rejected by Money.parse: half a paisa can't exist
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("10.005")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("amount 10.005 has too many decimals for INR"));
    }

    @Test
    void unknownBillingAccountIs422() throws Exception {
        when(paymentService.authorize(any(), any(), any(), any(), any())).thenThrow(new UnknownBillingAccountException(ACCOUNT_ID));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("10.00")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void captureWithoutBodyCapturesFullAmount() throws Exception {
        Payment payment = authorizedPayment(1_000_00);
        payment.capture(inr(1_000_00));
        when(paymentService.capture(eq(payment.getId()), isNull())).thenReturn(payment);

        mvc.perform(post("/payments/{id}/capture", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmount").value("1000.00"));
    }

    @Test
    void partialCapturePassesAmount() throws Exception {
        Payment payment = authorizedPayment(1_000_00);
        payment.capture(inr(600_00));
        when(paymentService.capture(payment.getId(), inr(600_00))).thenReturn(payment);

        mvc.perform(post("/payments/{id}/capture", payment.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"600.00\",\"currency\":\"INR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("1000.00"))
                .andExpect(jsonPath("$.capturedAmount").value("600.00"));
    }

    @Test
    void captureAmountWithoutCurrencyIs400() throws Exception {
        mvc.perform(post("/payments/{id}/capture", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"600.00\"}"))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).capture(any(), any());
    }

    @Test
    void captureInWrongStateIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.capture(eq(id), any()))
                .thenThrow(new IllegalPaymentStateException(id, PaymentStatus.VOIDED, PaymentStatus.CAPTURED));

        mvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("VOIDED"));
    }

    @Test
    void voidReturns200() throws Exception {
        Payment payment = authorizedPayment(1_000_00);
        payment.voidAuthorization();
        when(paymentService.voidAuthorization(payment.getId())).thenReturn(payment);

        mvc.perform(post("/payments/{id}/void", payment.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void refundReturns201WithLocation() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Refund refund = new Refund(paymentId, inr(300_00), "re_test", "item returned");
        when(paymentService.refund(KEY, paymentId, inr(300_00), "item returned")).thenReturn(refund);

        mvc.perform(post("/payments/{id}/refunds", paymentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"300.00\",\"currency\":\"INR\",\"reason\":\"item returned\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/payments/" + paymentId + "/refunds/" + refund.getId()))
                .andExpect(jsonPath("$.amount").value("300.00"))
                .andExpect(jsonPath("$.reason").value("item returned"));
    }

    @Test
    void refundWithoutBodyRefundsEverythingLeft() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.refund(eq(KEY), eq(paymentId), isNull(), isNull()))
                .thenReturn(new Refund(paymentId, inr(1_000_00), "re_test", null));

        mvc.perform(post("/payments/{id}/refunds", paymentId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("1000.00"));
    }

    @Test
    void refundAmountWithoutCurrencyIs400() throws Exception {
        mvc.perform(post("/payments/{id}/refunds", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).refund(any(), any(), any(), any());
    }

    @Test
    void refundOfUncapturedPaymentIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.refund(any(), eq(id), any(), any()))
                .thenThrow(new IllegalPaymentStateException(id, PaymentStatus.AUTHORIZED, PaymentStatus.REFUNDED));

        mvc.perform(post("/payments/{id}/refunds", id))
                .andExpect(status().isConflict());
    }

    @Test
    void listsRefunds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.refunds(paymentId)).thenReturn(List.of(
                new Refund(paymentId, inr(100_00), "re_1", null),
                new Refund(paymentId, inr(200_00), "re_2", null)));

        mvc.perform(get("/payments/{id}/refunds", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value("100.00"))
                .andExpect(jsonPath("$[1].amount").value("200.00"));
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.get(id)).thenThrow(new PaymentNotFoundException(id));

        mvc.perform(get("/payments/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        mvc.perform(get("/payments/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void processorFailureIs502WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.voidAuthorization(id)).thenThrow(new PaymentProcessorException("internal acquirer detail"));

        mvc.perform(post("/payments/{id}/void", id))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("the payment processor refused the request"));
    }

    // ---- Day 19: risk context + review (ADMIN only) ----

    @Test
    void authorizePassesTheClientIpAndDeviceToTheRiskCheck() throws Exception {
        when(paymentService.authorize(any(), any(), any(), any(), any())).thenReturn(authorizedPayment(1_000_00));

        mvc.perform(post("/payments").header("X-Forwarded-For", "203.0.113.9, 10.0.0.2").header("X-Device-Id", "dev-42")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("1000.00")))
                .andExpect(status().isCreated());

        verify(paymentService).authorize(any(), any(), any(), any(), eq(new RiskContext("203.0.113.9", "dev-42")));
    }

    @Test
    void reviewWithoutTheAdminRoleIs403AndChangesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        for (String role : new String[] {null, "USER", "admin"}) {
            MockHttpServletRequestBuilder request = post("/payments/{id}/review", id).header("X-User-Id", "u@test.com")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}");
            if (role != null) {
                request.header("X-User-Role", role);
            }
            mvc.perform(request).andExpect(status().isForbidden());
        }
        verify(paymentService, never()).review(any(), any(), any(), any());
    }

    @Test
    void adminReviewNamesTheReviewer() throws Exception {
        UUID id = UUID.randomUUID();
        Payment payment = Payment.create(ACCOUNT_ID, inr(60_000_00));
        payment.holdForReview();
        payment.review(Payment.ReviewDecision.REJECTED, "admin@test.com", "stolen card", java.time.Instant.now());
        when(paymentService.review(id, Payment.ReviewDecision.REJECTED, "admin@test.com", "stolen card")).thenReturn(payment);

        mvc.perform(post("/payments/{id}/review", id).header("X-User-Id", "admin@test.com").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"note\":\"stolen card\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("risk_review_rejected"))
                .andExpect(jsonPath("$.review.decision").value("REJECTED"))
                .andExpect(jsonPath("$.review.reviewedBy").value("admin@test.com"));
    }

    @Test
    void reviewNeedsADecision() throws Exception {
        mvc.perform(post("/payments/{id}/review", UUID.randomUUID()).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).review(any(), any(), any(), any());
    }

    // ---- Day 19b: chargebacks (ADMIN only) ----

    @Test
    void recordingAChargebackNeedsTheAdminRole() throws Exception {
        mvc.perform(post("/payments/{id}/chargebacks", UUID.randomUUID()).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"fraud\"}"))
                .andExpect(status().isForbidden());
        verify(paymentService, never()).chargeBack(any(), any(), any(), any(), any());
    }

    @Test
    void adminRecordsAChargeback() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Chargeback chargeback = new Chargeback(UUID.randomUUID(), paymentId, inr(300_00), "fraud", "CASE-9",
                java.math.BigDecimal.ONE, java.time.Instant.now());
        when(paymentService.chargeBack(any(), eq(paymentId), eq(inr(300_00)), eq("fraud"), eq("CASE-9"))).thenReturn(chargeback);

        mvc.perform(post("/payments/{id}/chargebacks", paymentId).header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"300.00\",\"currency\":\"INR\",\"reason\":\"fraud\",\"caseReference\":\"CASE-9\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/payments/" + paymentId + "/chargebacks/" + chargeback.getId()))
                .andExpect(jsonPath("$.amount").value("300.00"))
                .andExpect(jsonPath("$.reason").value("fraud"));
    }

    @Test
    void aChargebackNeedsAKnownReason() throws Exception {
        for (String body : new String[] {"{}", "{\"reason\":\"Fraud\"}", "{\"reason\":\"4111111111111111\"}",
                "{\"reason\":\"fraud\",\"amount\":\"1.00\"}"}) {
            mvc.perform(post("/payments/{id}/chargebacks", UUID.randomUUID()).header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(paymentService, never()).chargeBack(any(), any(), any(), any(), any());
    }

    /** Every POST here moves money, so every POST carries an Idempotency-Key. */
    private static MockHttpServletRequestBuilder post(String uriTemplate, Object... uriVariables) {
        return MockMvcRequestBuilders.post(uriTemplate, uriVariables).header("Idempotency-Key", KEY);
    }

    private static String createBody(String amount) {
        return """
                {"billingAccountId":"%s","amount":"%s","currency":"INR","paymentMethodToken":"pm_card_visa"}
                """.formatted(ACCOUNT_ID, amount);
    }

    private static Payment authorizedPayment(long paise) {
        Payment payment = Payment.create(ACCOUNT_ID, inr(paise));
        payment.authorize("auth_test");
        return payment;
    }

    private static Money inr(long paise) {
        return Money.ofMinor(paise, "INR");
    }
}
