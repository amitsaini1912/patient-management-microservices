package org.pm.patientservice.grpc;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BillingServiceGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(
            BillingServiceGrpcClient.class);
    private final BillingServiceGrpc.BillingServiceBlockingStub blockingStub; // Provides blocking
     // In other words synchronous client calls to the grpc server running on billing-service
    // if anytime we make calls to billing service using this blockingStub execution is going to wait for
    // the response come back from the server -> Similar to restful calls

    public BillingServiceGrpcClient(
            @Value("${billing.service.address:localhost}") String serverAddress,
            @Value("${billing.service.grpc.port:9001}") int serverPort) {

        log.info("Connecting to Billing Service GRPC service at {}:{}",
                serverAddress, serverPort);

        // Initialize blockingStub or GRPC client
        ManagedChannel channel = ManagedChannelBuilder.forAddress(serverAddress,
                serverPort).usePlaintext().build(); // UsePlaintext - disable encryption

        blockingStub = BillingServiceGrpc.newBlockingStub(channel);
    }

    @CircuitBreaker(name = "billingService", fallbackMethod = "createBillingAccountFallback")
    public BillingResponse createBillingAccount(String patientId, String name,
                                                String email) {

        BillingRequest request = BillingRequest.newBuilder().setPatientId(patientId)
                .setName(name).setEmail(email).build();

        BillingResponse response = blockingStub.createBillingAccount(request);
        log.info("Received response from billing service via GRPC: {}", response);
        return response;
    }

    // FALLBACK — same return type, same params, PLUS a Throwable at the end
    public BillingResponse createBillingAccountFallback(String patientId, String name,
                                                        String email, Throwable t) {
        log.error("Billing service unavailable — fallback triggered for patient {}. Reason: {}",
                patientId, t.getMessage());

        // Plan B: return a safe default so patient registration still succeeds.
        return BillingResponse.newBuilder()
                .setAccountId("PENDING")
                .setStatus("BILLING_UNAVAILABLE")
                .build();
    }
}
