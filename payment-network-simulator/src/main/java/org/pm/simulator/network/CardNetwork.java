package org.pm.simulator.network;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.pm.simulator.issuer.Issuer;
import org.pm.simulator.issuer.IssuerUnavailableException;
import org.pm.simulator.network.Iso8583.AuthorizationRequest;
import org.pm.simulator.network.Iso8583.AuthorizationResponse;
import org.pm.simulator.network.Iso8583.ReversalReason;
import org.pm.simulator.network.Iso8583.ReversalRequest;
import org.pm.simulator.network.Iso8583.ReversalResponse;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The card scheme (Visa/Mastercard's switch): routes 0100/0400 from the acquirer to the issuer and back.
 *
 * Two jobs besides routing:
 *   - field 6: converts the amount into the cardholder's billing currency at the scheme rate
 *   - timeouts: if the issuer doesn't answer within {@code issuerTimeout}, the network answers 91 itself and sends
 *     the issuer a 0400 TIMEOUT reversal. The issuer may have approved and held the money; nobody will ever
 *     capture it, so without the reversal the cardholder's money would stay blocked until the hold expires.
 * The issuer call is NOT cancelled on timeout: a real issuer doesn't stop because we stopped waiting.
 */
@Component
public class CardNetwork implements DisposableBean {

    private final Issuer issuer;
    private final NetworkProperties properties;
    private final NetworkLog messages;
    private final TraceNumbers trace;
    private final ExecutorService issuerLink = Executors.newVirtualThreadPerTaskExecutor();

    public CardNetwork(Issuer issuer, NetworkProperties properties, NetworkLog messages, TraceNumbers trace) {
        this.issuer = issuer;
        this.properties = properties;
        this.messages = messages;
        this.trace = trace;
    }

    public boolean supports(String currency) {
        return currency.equals(issuer.billingCurrency()) || properties.schemeRates().containsKey(currency);
    }

    public AuthorizationResponse authorize(AuthorizationRequest fromAcquirer) {
        AuthorizationRequest request = fromAcquirer.withBilling(
                toBilling(fromAcquirer.amountMinor(), fromAcquirer.currency()), issuer.billingCurrency());
        messages.request(request);

        CompletableFuture<AuthorizationResponse> answer = CompletableFuture.supplyAsync(() -> issuer.authorize(request), issuerLink);
        AuthorizationResponse response;
        try {
            response = answer.get(properties.issuerTimeout().toMillis(), TimeUnit.MILLISECONDS);
            messages.response(response, null);
        } catch (TimeoutException e) {
            answer.whenComplete((late, error) -> {
                if (late != null) {
                    messages.response(late, "late, after timeout reversal: dropped");
                }
            });
            reverse(new ReversalRequest(trace.nextStan(), request.rrn(), request.stan(), ReversalReason.TIMEOUT));
            response = standIn(request, "issuer timeout");
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof IssuerUnavailableException)) {
                throw new IllegalStateException("issuer failed", e.getCause());
            }
            response = standIn(request, "issuer unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response = standIn(request, "interrupted");
        }
        return response;
    }

    public ReversalResponse reverse(ReversalRequest request) {
        messages.reversal(request);
        ReversalResponse response = issuer.reverse(request);
        messages.reversalResponse(response);
        return response;
    }

    /** Capture presentment (clearing). In real life a batch file the next day; here at once. */
    public void presentCapture(String rrn, long amountMinor, String currency) {
        long billing = toBilling(amountMinor, currency);
        issuer.presentCapture(rrn, billing);
        messages.clearing(rrn, "capture", amountMinor, currency, billing, issuer.billingCurrency());
    }

    /** Refund presentment (clearing): a credit to the card. */
    public void presentRefund(String rrn, long amountMinor, String currency) {
        long billing = toBilling(amountMinor, currency);
        issuer.presentRefund(rrn, billing);
        messages.clearing(rrn, "refund", amountMinor, currency, billing, issuer.billingCurrency());
    }

    /** Field 6 = field 4 x scheme rate, rounded HALF_EVEN to the billing currency's minor unit. */
    long toBilling(long amountMinor, String currency) {
        String billingCurrency = issuer.billingCurrency();
        if (currency.equals(billingCurrency)) {
            return amountMinor;
        }
        BigDecimal rate = properties.schemeRates().get(currency);
        if (rate == null) {
            throw new IllegalArgumentException("no scheme rate for " + currency);
        }
        return BigDecimal.valueOf(amountMinor)
                .movePointLeft(Currency.getInstance(currency).getDefaultFractionDigits())
                .multiply(rate)
                .setScale(Currency.getInstance(billingCurrency).getDefaultFractionDigits(), RoundingMode.HALF_EVEN)
                .unscaledValue().longValueExact();
    }

    /** The network answers in the issuer's place: never approves, only 91. */
    private AuthorizationResponse standIn(AuthorizationRequest request, String why) {
        AuthorizationResponse response = new AuthorizationResponse(request.stan(), request.rrn(), null,
                ResponseCode.ISSUER_UNAVAILABLE);
        messages.response(response, "stand-in: " + why);
        return response;
    }

    @Override
    public void destroy() {
        issuerLink.shutdownNow();
    }
}
