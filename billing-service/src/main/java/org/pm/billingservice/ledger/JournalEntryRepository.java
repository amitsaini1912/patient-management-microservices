package org.pm.billingservice.ledger;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    boolean existsByReversalOf(UUID reversalOf);

    /** Sum of DEBIT lines minus sum of CREDIT lines for one account (0 if it has no lines). */
    @Query("""
            select coalesce(sum(case when l.direction = :debit then l.amountMinor else -l.amountMinor end), 0)
            from EntryLine l
            where l.account.id = :accountId
            """)
    long debitsMinusCredits(@Param("accountId") UUID accountId, @Param("debit") Direction debit);
}
