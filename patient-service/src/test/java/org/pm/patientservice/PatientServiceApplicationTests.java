package org.pm.patientservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "grpc.server.port=0") // several test contexts: no fixed port
@Import(TestcontainersConfiguration.class) // real Postgres since ADR 0030 (was H2)
class PatientServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
