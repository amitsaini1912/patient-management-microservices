package org.pm.simulator.acquirer;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.pm.simulator.acquirer.AuthorizationState.Status;
import org.pm.simulator.card.CardVaultClient;
import org.pm.simulator.network.CardNetwork;
import org.pm.simulator.network.Iso8583;
import org.pm.simulator.network.ResponseCode;
import org.pm.simulator.network.TraceNumbers;
import org.pm.simulator.settlement.SettlementBook;
import org.pm.simulator.settlement.SettlementRecord;
import org.pm.simulator.webhook.WebhookDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The acquirer's books, in memory.
 *
 * Day 11: an authorization goes to the card network. The token is detokenized at card-vault, the PAN goes into a
 * 0100, and the issuer's field 39 decides (test cards: see {@link org.pm.simulator.issuer.Issuer}). Void sends a
 * 0400 reversal (releases the hold), capture and refund are presented to the issuer (clearing).
 *
 * Idempotent like Stripe: a successful call is stored under its Idempotency-Key; repeating the key returns the
 * stored result and does nothing (no second capture, no second webhook). The same key with a different request
 * is refused (422). Refusals are not stored, so a retry is evaluated again. A decline - also a retryable 91 - is
 * a result, so it IS stored: retrying a 91 needs a new authorization with a new key.
 */
@Service
public class AcquirerService {

    private final Map<String, AuthorizationState> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Completed> completed = new ConcurrentHashMap<>();
    private final WebhookDispatcher webhooks;
    private final SettlementBook settlements;
    private final CardVaultClient cardVault;
    private final CardNetwork network;
    private final TraceNumbers trace;
    private final Clock clock = Clock.systemUTC();

    public AcquirerService(WebhookDispatcher webhooks, SettlementBook settlements, CardVaultClient cardVault,
                           CardNetwork network, TraceNumbers trace) {
        this.webhooks = webhooks;
        this.settlements = settlements;
        this.cardVault = cardVault;
        this.network = network;
        this.trace = trace;
    }

    public AuthorizationState authorize(String key, String merchantReference, long amountMinor, String currency,
                                        String paymentMethodToken) {
        String fingerprint = "authorize|" + merchantReference + "|" + amountMinor + "|" + currency + "|" + paymentMethodToken;
        return once(key, fingerprint, () -> {
            if (!network.supports(currency)) {
                throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "currency_not_supported",
                        "the card network has no rate for " + currency);
            }
            CardVaultClient.Card card = detokenize(paymentMethodToken); // the PAN lives only in this call
            String stan = trace.nextStan();
            String rrn = trace.rrn(stan);
            Iso8583.AuthorizationResponse answer = network.authorize(new Iso8583.AuthorizationRequest(card.pan(),
                    amountMinor, 0, clock.instant(), stan, card.expiry(), rrn, currency, null));
            ResponseCode code = answer.responseCode();

            AuthorizationState auth = new AuthorizationState("auth_" + UUID.randomUUID(), merchantReference, currency,
                    amountMinor, 0, 0, code.approved() ? Status.AUTHORIZED : Status.DECLINED, code.declineReason(),
                    code.code(), code.retryable(), stan, rrn, answer.authorizationCode(), clock.instant());
            authorizations.put(auth.id(), auth);
            publish(code.approved() ? "payment.authorized" : "payment.failed", auth, Map.of());
            return auth;
        });
    }

    private CardVaultClient.Card detokenize(String token) {
        try {
            return cardVault.detokenize(token);
        } catch (CardVaultClient.CardNotFoundException e) {
            throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "card_not_found", e.getMessage());
        } catch (CardVaultClient.CardVaultUnavailableException e) {
            // nothing was sent to the network: safe to retry with the same key
            throw new AcquirerException(HttpStatus.SERVICE_UNAVAILABLE, "card_vault_unavailable", e.getMessage());
        }
    }

    public AuthorizationState capture(String key, String authorizationId, long amountMinor, String currency) {
        return once(key, "capture|" + authorizationId + "|" + amountMinor + "|" + currency, () -> {
            AuthorizationState captured = change(authorizationId, auth -> {
                requireStatus(auth, Status.AUTHORIZED, "capture");
                requireCurrency(auth, currency);
                if (amountMinor <= 0 || amountMinor > auth.authorizedMinor()) {
                    throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "amount_invalid",
                            "capture amount must be between 1 and " + auth.authorizedMinor());
                }
                return auth.with(Status.CAPTURED, amountMinor, 0);
            });
            network.presentCapture(captured.rrn(), amountMinor, captured.currency());
            settlements.record("cap_" + UUID.randomUUID(), SettlementRecord.Type.CAPTURE, captured.id(),
                    captured.merchantReference(), amountMinor, captured.currency());
            publish("payment.captured", captured, Map.of());
            return captured;
        });
    }

    public AuthorizationState voidAuthorization(String key, String authorizationId) {
        return once(key, "void|" + authorizationId, () -> {
            AuthorizationState voided = change(authorizationId, auth -> {
                requireStatus(auth, Status.AUTHORIZED, "void");
                return auth.with(Status.VOIDED, 0, 0);
            });
            network.reverse(new Iso8583.ReversalRequest(trace.nextStan(), voided.rrn(), voided.stan(),
                    Iso8583.ReversalReason.VOID));
            publish("payment.voided", voided, Map.of());
            return voided;
        });
    }

    public RefundResult refund(String key, String authorizationId, long amountMinor, String currency,
                               String merchantRefundReference) {
        String fingerprint = "refund|" + authorizationId + "|" + amountMinor + "|" + currency + "|" + merchantRefundReference;
        return once(key, fingerprint, () -> {
            AuthorizationState refunded = change(authorizationId, auth -> {
                requireStatus(auth, Status.CAPTURED, "refund");
                requireCurrency(auth, currency);
                long left = auth.capturedMinor() - auth.refundedMinor();
                if (amountMinor <= 0 || amountMinor > left) {
                    throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "amount_invalid",
                            "refund amount must be between 1 and " + left);
                }
                return auth.with(Status.CAPTURED, auth.capturedMinor(), auth.refundedMinor() + amountMinor);
            });
            network.presentRefund(refunded.rrn(), amountMinor, refunded.currency());
            RefundResult result = new RefundResult("re_" + UUID.randomUUID(), refunded);
            settlements.record(result.id(), SettlementRecord.Type.REFUND, refunded.id(), refunded.merchantReference(),
                    amountMinor, refunded.currency());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("refund_id", result.id());
            details.put("refund_amount_minor", amountMinor);
            if (merchantRefundReference != null) {
                details.put("merchant_refund_reference", merchantRefundReference);
            }
            publish("payment.refunded", refunded, details);
            return result;
        });
    }

    public AuthorizationState get(String authorizationId) {
        AuthorizationState auth = authorizations.get(authorizationId);
        if (auth == null) {
            throw new AcquirerException(HttpStatus.NOT_FOUND, "not_found", "no authorization " + authorizationId);
        }
        return auth;
    }

    public record RefundResult(String id, AuthorizationState authorization) {}

    private record Completed(String fingerprint, Object result) {}

    private interface Change {
        AuthorizationState apply(AuthorizationState current);
    }

    /** compute() is atomic per authorization: two captures of one hold can't both pass the checks. */
    private AuthorizationState change(String authorizationId, Change change) {
        get(authorizationId); // 404 before compute, so compute never sees a missing key
        return authorizations.compute(authorizationId, (id, current) -> change.apply(current));
    }

    /**
     * computeIfAbsent is atomic per key: concurrent calls with one key run {@code call} once and all get its result.
     * A refusal (exception) stores nothing, so a retry runs again.
     */
    @SuppressWarnings("unchecked")
    private <T> T once(String key, String fingerprint, Supplier<T> call) {
        if (key == null || key.isBlank()) {
            throw new AcquirerException(HttpStatus.BAD_REQUEST, "idempotency_key_missing", "Idempotency-Key header is required");
        }
        Completed done = completed.computeIfAbsent(key, k -> new Completed(fingerprint, call.get()));
        if (!Objects.equals(done.fingerprint(), fingerprint)) {
            throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                    "this Idempotency-Key was used for a different request");
        }
        return (T) done.result();
    }

    private void publish(String type, AuthorizationState auth, Map<String, Object> details) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authorization_id", auth.id());
        data.put("merchant_reference", auth.merchantReference());
        data.put("status", auth.status().name());
        data.put("currency", auth.currency());
        data.put("amount_minor", auth.authorizedMinor());
        data.put("captured_minor", auth.capturedMinor());
        data.put("refunded_minor", auth.refundedMinor());
        if (auth.declineReason() != null) {
            data.put("decline_reason", auth.declineReason());
        }
        data.put("network_response_code", auth.networkResponseCode());
        data.put("retryable", auth.retryable());
        data.putAll(details);
        webhooks.publish(type, data);
    }

    private static void requireStatus(AuthorizationState auth, Status expected, String operation) {
        if (auth.status() != expected) {
            throw new AcquirerException(HttpStatus.CONFLICT, "invalid_state",
                    "cannot " + operation + " authorization " + auth.id() + " in status " + auth.status());
        }
    }

    private static void requireCurrency(AuthorizationState auth, String currency) {
        if (!auth.currency().equals(currency)) {
            throw new AcquirerException(HttpStatus.UNPROCESSABLE_ENTITY, "currency_mismatch",
                    "authorization is in " + auth.currency() + ", request is in " + currency);
        }
    }
}
