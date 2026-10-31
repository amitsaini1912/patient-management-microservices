package org.pm.billingservice.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargebackRepository extends JpaRepository<Chargeback, UUID> {

    List<Chargeback> findByPaymentIdOrderByCreatedAt(UUID paymentId);
}
