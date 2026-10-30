package org.pm.simulator.issuer;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.pm.simulator.network.Iso8583.AuthorizationRequest;
import org.pm.simulator.network.Iso8583.AuthorizationResponse;
import org.pm.simulator.network.Iso8583.ReversalReason;
import org.pm.simulator.network.Iso8583.ReversalRequest;
import org.pm.simulator.network.ResponseCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The issuer on its own: decisions, holds, and reversals that arrive before or after their 0100. */
class IssuerTest {

    private static final String CARD = "4242424242424242";
    private static final YearMonth VALID = YearMonth.of(2099, 12);

    private final Issuer issuer = new Issuer(new IssuerProperties("INR", 1_000_00, Duration.ZERO));
    private int stan;

    @Test
    void aReversalThatOvertakesIts0100IsRememberedSoTheLate0100HoldsNothing() {
        AuthorizationRequest delayed = request(CARD, 300_00, VALID);

        issuer.reverse(new ReversalRequest("900001", delayed.rrn(), delayed.stan(), ReversalReason.TIMEOUT));
        AuthorizationResponse late = issuer.authorize(delayed);

        assertThat(late.responseCode()).isEqualTo(ResponseCode.APPROVED); // nobody is listening any more
        assertThat(issuer.account(CARD).orElseThrow().heldMinor()).isZero();
        assertThat(issuer.account(CARD).orElseThrow().availableMinor()).isEqualTo(1_000_00);
    }

    @Test
    void duplicateReversalIsAcknowledgedAndChangesNothing() {
        AuthorizationRequest a = request(CARD, 300_00, VALID);
        AuthorizationRequest b = request(CARD, 200_00, VALID);
        issuer.authorize(a);
        issuer.authorize(b);

        ResponseCode first = issuer.reverse(new ReversalRequest("900001", a.rrn(), a.stan(), ReversalReason.VOID)).responseCode();
        ResponseCode again = issuer.reverse(new ReversalRequest("900002", a.rrn(), a.stan(), ReversalReason.VOID)).responseCode();

        assertThat(first).isEqualTo(ResponseCode.APPROVED);
        assertThat(again).isEqualTo(ResponseCode.APPROVED);
        assertThat(issuer.account(CARD).orElseThrow().heldMinor()).isEqualTo(200_00); // b's hold untouched
    }

    @Test
    void expiredByTheMessageOrByTheBankIs54() {
        assertThat(issuer.authorize(request(CARD, 100, YearMonth.of(2020, 5))).responseCode())
                .isEqualTo(ResponseCode.EXPIRED_CARD);
        // the vault may still have a valid expiry for a card the bank has cancelled
        assertThat(issuer.authorize(request(Issuer.EXPIRED, 100, VALID)).responseCode())
                .isEqualTo(ResponseCode.EXPIRED_CARD);
    }

    @Test
    void unavailableIssuerThrowsSoTheNetworkCanAnswer91() {
        assertThatThrownBy(() -> issuer.authorize(request(Issuer.UNAVAILABLE, 100, VALID)))
                .isInstanceOf(IssuerUnavailableException.class)
                .hasMessageContaining("****0911").hasMessageNotContaining(Issuer.UNAVAILABLE);
    }

    @Test
    void cannotClearMoreThanIsHeld() {
        AuthorizationRequest a = request(CARD, 300_00, VALID);
        issuer.authorize(a);

        assertThatThrownBy(() -> issuer.presentCapture(a.rrn(), 300_01)).isInstanceOf(IllegalStateException.class);
        assertThat(issuer.account(CARD).orElseThrow().heldMinor()).isEqualTo(300_00); // hold kept
        issuer.presentCapture(a.rrn(), 300_00);
        assertThatThrownBy(() -> issuer.presentCapture(a.rrn(), 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentAuthorizationsNeverHoldMoreThanTheBalance() throws Exception {
        // balance 1,000.00; 20 threads at once each ask for 100.00 -> exactly 10 approvals, 10 x 51
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseCode>> answers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            AuthorizationRequest r = request(CARD, 100_00, VALID);
            answers.add(pool.submit(() -> {
                go.await();
                return issuer.authorize(r).responseCode();
            }));
        }
        go.countDown();
        List<ResponseCode> codes = new ArrayList<>();
        for (Future<ResponseCode> answer : answers) {
            codes.add(answer.get());
        }
        pool.shutdown();

        assertThat(codes).filteredOn(c -> c == ResponseCode.APPROVED).hasSize(10);
        assertThat(codes).filteredOn(c -> c == ResponseCode.INSUFFICIENT_FUNDS).hasSize(10);
        assertThat(issuer.account(CARD).orElseThrow().availableMinor()).isZero();
    }

    private AuthorizationRequest request(String pan, long amountMinor, YearMonth expiry) {
        String s = "%06d".formatted(++stan);
        return new AuthorizationRequest(pan, amountMinor, amountMinor, Instant.now(), s, expiry, "630414" + s, "INR", "INR");
    }
}
