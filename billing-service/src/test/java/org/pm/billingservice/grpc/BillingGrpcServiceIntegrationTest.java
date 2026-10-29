package org.pm.billingservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.pm.billingservice.TestcontainersConfiguration;
import org.pm.billingservice.ledger.AccountType;
import org.pm.billingservice.ledger.LedgerAccount;
import org.pm.billingservice.ledger.LedgerService;
import org.pm.billingservice.money.Money;
import org.pm.billingservice.repository.BillingAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "grpc.server.port=0")
@Import(TestcontainersConfiguration.class)
class BillingGrpcServiceIntegrationTest {

    @Autowired
    private BillingGrpcService billingGrpcService;

    @Autowired
    private BillingAccountRepository billingAccountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void createsRealAccountInsteadOfStub() {
        String patientId = UUID.randomUUID().toString();

        RecordingObserver observer = call(patientId);

        assertThat(observer.response.getStatus()).isEqualTo("ACTIVE");
        assertThat(observer.response.getAccountId()).isNotEqualTo("12345"); // the old hard-coded stub value
        assertThat(billingAccountRepository.findByPatientId(UUID.fromString(patientId))).isPresent();
    }

    @Test
    void retryForSamePatientReturnsSameAccount() {
        String patientId = UUID.randomUUID().toString();

        String first = call(patientId).response.getAccountId();
        String second = call(patientId).response.getAccountId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void newAccountGetsEmptyPatientReceivable() {
        String accountId = call(UUID.randomUUID().toString()).response.getAccountId();

        LedgerAccount receivable = ledgerService.patientReceivable(UUID.fromString(accountId));

        assertThat(receivable.getType()).isEqualTo(AccountType.ASSET);
        assertThat(ledgerService.balanceOf(receivable)).isEqualTo(Money.ofMinor(0, "INR"));
    }

    @Test
    void nonUuidPatientIdIsInvalidArgument() {
        RecordingObserver observer = call("1");

        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private RecordingObserver call(String patientId) {
        RecordingObserver observer = new RecordingObserver();
        billingGrpcService.createBillingAccount(BillingRequest.newBuilder()
                .setPatientId(patientId).setName("John Doe").setEmail("john@test.com").build(), observer);
        return observer;
    }

    // Captures what the service sends back, instead of a real network client.
    private static class RecordingObserver implements StreamObserver<BillingResponse> {
        BillingResponse response;
        Throwable error;

        @Override public void onNext(BillingResponse value) { response = value; }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onCompleted() { }
    }
}
