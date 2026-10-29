package org.pm.billingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "grpc.server.port=0") // random gRPC port so tests never clash with a running app
@Import(TestcontainersConfiguration.class)
class BillingServiceApplicationTests {

    @Test
    void contextLoads() {
        // boots the whole app: Flyway runs V1 on real Postgres, Hibernate validates the entities against it
    }

}
