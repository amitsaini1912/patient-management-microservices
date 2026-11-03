package org.pm.analyticservice;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pm.analyticservice.model.Claim;
import org.pm.analyticservice.repository.ClaimRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Hibernate N+1 problem and its fix by counting the SQL statements Hibernate really sends.
 * Seed data (V2) has 5 APPROVED claims on 5 different visits.
 *   naive  findByStatus + claim.getVisit()  -> 1 query for claims + 1 per visit = 6 queries
 *   fixed  findByStatusWithVisit (JOIN FETCH) -> 1 query
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ClaimQueryCountTest {

    @Autowired ClaimRepository claims;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;

    Statistics stats;

    @BeforeEach
    void reset() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    @Test
    void naiveLoadHasNPlusOneQueries() {
        int touched = tx.execute(s -> touchVisits(claims.findByStatus("APPROVED")));

        assertThat(touched).isEqualTo(5);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(6);   // 1 + N
    }

    @Test
    void joinFetchLoadsEverythingInOneQuery() {
        int touched = tx.execute(s -> touchVisits(claims.findByStatusWithVisit("APPROVED")));

        assertThat(touched).isEqualTo(5);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    private static int touchVisits(List<Claim> list) {
        list.forEach(c -> c.getVisit().getDepartment());   // forces the lazy visit to load
        return list.size();
    }
}
