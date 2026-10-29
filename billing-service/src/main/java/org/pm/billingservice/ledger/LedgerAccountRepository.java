package org.pm.billingservice.ledger;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByBillingAccountIdAndPurpose(UUID billingAccountId, AccountPurpose purpose);

    // system accounts have no billing account
    Optional<LedgerAccount> findByPurposeAndCurrencyAndBillingAccountIdIsNull(AccountPurpose purpose, Currency currency);
}
