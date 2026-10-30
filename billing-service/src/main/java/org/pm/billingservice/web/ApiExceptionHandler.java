package org.pm.billingservice.web;

import jakarta.servlet.http.HttpServletRequest;
import org.pm.billingservice.idempotency.IdempotencyFilter;
import org.pm.billingservice.money.CurrencyMismatchException;
import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.pm.billingservice.payment.PaymentNotFoundException;
import org.pm.billingservice.payment.UnknownBillingAccountException;
import org.pm.billingservice.processor.PaymentProcessorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns domain errors into RFC 7807 problem responses (application/problem+json).
 * Extends ResponseEntityExceptionHandler so Spring's own errors (bean validation, unreadable JSON)
 * come back in the same format.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The request is valid, but the payment is in the wrong state for it (e.g. capture after void). */
    @ExceptionHandler(IllegalPaymentStateException.class)
    ProblemDetail illegalPaymentState(IllegalPaymentStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Illegal payment state");
        problem.setProperty("currentStatus", e.getFrom());
        problem.setProperty("requestedStatus", e.getTo());
        return problem;
    }

    /**
     * Someone else changed the same payment between our read and our write. Safe for the client to re-read and retry.
     * Marked retryable: the idempotency filter must not store this 409 and replay it to every retry of the key.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(ObjectOptimisticLockingFailureException e, HttpServletRequest request) {
        request.setAttribute(IdempotencyFilter.RETRYABLE_ATTRIBUTE, Boolean.TRUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the resource was changed by another request; re-read it and retry");
        problem.setTitle("Concurrent modification");
        return problem;
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail paymentNotFound(PaymentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Payment not found");
        return problem;
    }

    /** The URL exists, but the body refers to something that doesn't. */
    @ExceptionHandler(UnknownBillingAccountException.class)
    ProblemDetail unknownBillingAccount(UnknownBillingAccountException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Unknown billing account");
        return problem;
    }

    /**
     * Bad amounts: too many decimals, more than authorized, unknown or mismatched currency.
     * Note: this catches every IllegalArgumentException, so a programming error that throws one
     * would also look like a client error. Acceptable in a service this size; revisit if it hides bugs.
     */
    @ExceptionHandler({IllegalArgumentException.class, CurrencyMismatchException.class})
    ProblemDetail invalidRequest(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    /** The processor refused or failed. Not the client's fault, and not ours: 502 Bad Gateway. */
    @ExceptionHandler(PaymentProcessorException.class)
    ProblemDetail processorFailed(PaymentProcessorException e) {
        log.warn("payment processor error: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "the payment processor refused the request");
        problem.setTitle("Payment processor error");
        return problem;
    }
}
