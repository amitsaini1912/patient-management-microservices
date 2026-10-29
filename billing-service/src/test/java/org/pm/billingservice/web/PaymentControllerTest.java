package org.pm.billingservice.web;

import org.junit.jupiter.api.Test;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.pm.billingservice.payment.Payment;
import org.pm.billingservice.payment.PaymentNotFoundException;
import org.pm.billingservice.payment.PaymentService;
import org.pm.billingservice.payment.PaymentStatus;
import org.pm.billingservice.payment.Refund;
import org.pm.billingservice.payment.UnknownBillingAccountException;
import org.pm.billingservice.processor.PaymentProcessorException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP layer only: JSON in/out, validation, status codes. PaymentService is a mock. */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void authorizeReturns201WithLocationAndDecimalAmounts() throws Exception {
        Payment payment = authorizedPayment(1_000_50);
        when(paymentService.authorize(ACCOUNT_ID, inr(1_000_50), "pm_card_visa")).thenReturn(payment);

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
        when(paymentService.authorize(any(), any(), any())).thenReturn(payment);

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(createBody("500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));
    }

    @Test
    void missingFieldsAre400ProblemJson() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));

        verify(paymentService, never()).authorize(any(), any(), any());
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
        when(paymentService.authorize(any(), any(), any())).thenThrow(new UnknownBillingAccountException(ACCOUNT_ID));

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
        when(paymentService.refund(paymentId, inr(300_00), "item returned")).thenReturn(refund);

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
        when(paymentService.refund(eq(paymentId), isNull(), isNull()))
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

        verify(paymentService, never()).refund(any(), any(), any());
    }

    @Test
    void refundOfUncapturedPaymentIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.refund(eq(id), any(), any()))
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
