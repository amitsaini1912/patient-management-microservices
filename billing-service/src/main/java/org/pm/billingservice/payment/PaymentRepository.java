package org.pm.billingservice.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * SELECT ... FOR UPDATE: for the step that RECORDS what the acquirer already did (Day 21, ADR 0021). Concurrent
     * recordings on one payment wait their turn instead of failing on @Version - with optimistic locking alone, 120
     * concurrent refunds on one payment exhausted the retries and 19 refunds done at the acquirer were not recorded.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Claims a batch of authorizations older than {@code cutoff} for the expiry job.
     * FOR UPDATE locks them until the transaction ends; SKIP LOCKED makes a second billing instance
     * skip rows the first one already claimed (instead of waiting for them), so each payment is
     * expired by exactly one instance and neither blocks the other.
     * Uses the partial index payment_authorized_expiry_idx.
     */
    @Query(value = """
            SELECT * FROM payment
            WHERE status = 'AUTHORIZED' AND authorized_at < :cutoff
            ORDER BY authorized_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Payment> lockAuthorizedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
