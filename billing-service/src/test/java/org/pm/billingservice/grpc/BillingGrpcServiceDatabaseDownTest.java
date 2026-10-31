package org.pm.billingservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.service.BillingAccountService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Day 23 (chaos): found under load with billing-db stopped - the caller got UNKNOWN and couldn't tell it may retry. */
class BillingGrpcServiceDatabaseDownTest {

    private final BillingAccountService accounts = mock(BillingAccountService.class);
    private final BillingGrpcService service = new BillingGrpcService(accounts);
    private final BillingRequest request = BillingRequest.newBuilder()
            .setPatientId(UUID.randomUUID().toString()).setName("P").setEmail("p@test.com").build();

    @Test
    void databaseDownIsUnavailableSoTheCallerRetries() {
        when(accounts.getOrCreateAccount(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("I/O error"));
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.createBillingAccount(request, observer(error));

        assertThat(Status.fromThrowable(error.get()).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void otherErrorsAreNotDressedUpAsTemporary() {
        when(accounts.getOrCreateAccount(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("bug"));

        assertThatThrownBy(() -> service.createBillingAccount(request, observer(new AtomicReference<>())))
                .isInstanceOf(DataIntegrityViolationException.class);   // grpc-java answers UNKNOWN, as before
    }

    private static StreamObserver<BillingResponse> observer(AtomicReference<Throwable> error) {
        return new StreamObserver<>() {
            @Override public void onNext(BillingResponse value) { }
            @Override public void onError(Throwable t) { error.set(t); }
            @Override public void onCompleted() { }
        };
    }
}
