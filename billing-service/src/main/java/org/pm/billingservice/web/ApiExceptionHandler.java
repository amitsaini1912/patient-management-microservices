package org.pm.billingservice.web;

import org.pm.billingservice.payment.IllegalPaymentStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns domain errors into RFC 7807 problem responses (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** The request is valid, but the payment is in the wrong state for it (e.g. capture after void). */
    @ExceptionHandler(IllegalPaymentStateException.class)
    ProblemDetail illegalPaymentState(IllegalPaymentStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Illegal payment state");
        problem.setProperty("currentStatus", e.getFrom());
        problem.setProperty("requestedStatus", e.getTo());
        return problem;
    }

    /** Someone else changed the same payment between our read and our write. Safe for the client to re-read and retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(ObjectOptimisticLockingFailureException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the resource was changed by another request; re-read it and retry");
        problem.setTitle("Concurrent modification");
        return problem;
    }
}
