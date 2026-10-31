package org.pm.billingservice.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc.BillingServiceImplBase;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.pm.billingservice.db.DatabaseErrors;
import org.pm.billingservice.model.BillingAccount;
import org.pm.billingservice.service.BillingAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

@GrpcService
public class BillingGrpcService extends BillingServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(
            BillingGrpcService.class);

    private final BillingAccountService billingAccountService;

    public BillingGrpcService(BillingAccountService billingAccountService) {
        this.billingAccountService = billingAccountService;
    }

    @Override
    public void createBillingAccount(BillingRequest billingRequest,
                                     StreamObserver<BillingResponse> responseObserver) {

        // log the id only - name/email are personal data and don't belong in logs
        log.info("createBillingAccount request received for patient {}", billingRequest.getPatientId());

        UUID patientId;
        try {
            patientId = UUID.fromString(billingRequest.getPatientId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("patientId must be a UUID").asRuntimeException());
            return;
        }

        BillingAccount account;
        try {
            account = billingAccountService.getOrCreateAccount(
                    patientId, billingRequest.getName(), billingRequest.getEmail());
        } catch (RuntimeException e) {
            if (!DatabaseErrors.isUnavailable(e)) {
                throw e;
            }
            // Day 23 (chaos): database down -> UNAVAILABLE (gRPC's "retry later"), not UNKNOWN. Safe to retry:
            // one account per patient (patient_id UNIQUE).
            log.warn("createBillingAccount: database unavailable ({})", e.getMessage());
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("temporarily unavailable, retry").asRuntimeException());
            return;
        }

        BillingResponse response = BillingResponse.newBuilder()
                .setAccountId(account.getId().toString())
                .setStatus(account.getStatus().name())
                .build();

        responseObserver.onNext(response); // we can use multiple onNext if we have to pass multiple responce
        responseObserver.onCompleted(); // This means response completed for this cycle
    }
}
