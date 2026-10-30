package org.pm.simulator.issuer;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.pm.simulator.network.Iso8583;
import org.pm.simulator.network.Iso8583.AuthorizationRequest;
import org.pm.simulator.network.Iso8583.AuthorizationResponse;
import org.pm.simulator.network.Iso8583.ReversalRequest;
import org.pm.simulator.network.Iso8583.ReversalResponse;
import org.pm.simulator.network.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The card-issuing bank, in memory. It plays the bank, so it is allowed to know card numbers (ADR 0013).
 *
 * Each card has a balance and holds. available = balance - holds.
 *   0100 approved      -> hold the amount (in the card's billing currency)
 *   0400 reversal      -> release the hold
 *   capture presentment -> the hold becomes a real debit; the rest of a partial capture is released
 *   refund presentment  -> credit the card
 *
 * Test cards (all Luhn-valid, so card-vault tokenizes them):
 *   4111111111111111, 5555555555554444 and any other card  -> 00, unless the amount is above what is available (51)
 *   4000000000000002  -> 05 do not honor
 *   4000000000009995  -> 51 insufficient funds (balance 0)
 *   4000000000000069  -> 54 expired: the bank cancelled this card in 01/2020, whatever expiry the vault has
 *   4000000000000911  -> the bank's host is down (the network answers 91)
 *   4000000000007775  -> approves and holds, but answers too late (the network times out, answers 91, and reverses)
 *
 * A reversal can overtake its own 0100 (the 0100 was delayed, the acquirer timed out and reversed). The issuer
 * remembers every reversed RRN, so when that 0100 finally arrives no hold is placed: otherwise the money would
 * stay blocked on the cardholder's account for days with no payment behind it.
 *
 * All state is behind one lock (it's a simulator); the slow card's wait happens outside it.
 */
@Component
public class Issuer {

    public static final String VISA = "4111111111111111";
    public static final String MASTERCARD = "5555555555554444";
    public static final String DO_NOT_HONOR = "4000000000000002";
    public static final String INSUFFICIENT_FUNDS = "4000000000009995";
    public static final String EXPIRED = "4000000000000069";
    public static final String UNAVAILABLE = "4000000000000911";
    public static final String SLOW = "4000000000007775";

    private static final Logger log = LoggerFactory.getLogger(Issuer.class);
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    enum Behaviour { NORMAL, DO_NOT_HONOR, UNAVAILABLE, SLOW }

    private static final class Account {
        final String pan;
        final Behaviour behaviour;
        final YearMonth closedAfter; // null = valid until its own expiry date
        long balanceMinor;
        final Map<String, Long> holds = new LinkedHashMap<>(); // rrn -> held amount

        Account(String pan, Behaviour behaviour, YearMonth closedAfter, long balanceMinor) {
            this.pan = pan;
            this.behaviour = behaviour;
            this.closedAfter = closedAfter;
            this.balanceMinor = balanceMinor;
        }

        long heldMinor() {
            return holds.values().stream().mapToLong(Long::longValue).sum();
        }

        long availableMinor() {
            return balanceMinor - heldMinor();
        }
    }

    /** What tests and /admin may see of an account: never the PAN. */
    public record AccountView(String card, String currency, long balanceMinor, long heldMinor, long availableMinor,
                             int holds) {}

    private final IssuerProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, String> cardOf = new HashMap<>();   // approved rrn -> pan
    private final Set<String> reversed = new HashSet<>();        // every reversed rrn, also ones not seen yet

    public Issuer(IssuerProperties properties) {
        this.properties = properties;
        long balance = properties.defaultBalanceMinor();
        open(VISA, Behaviour.NORMAL, null, balance);
        open(MASTERCARD, Behaviour.NORMAL, null, balance);
        open(DO_NOT_HONOR, Behaviour.DO_NOT_HONOR, null, balance);
        open(INSUFFICIENT_FUNDS, Behaviour.NORMAL, null, 0);
        open(EXPIRED, Behaviour.NORMAL, YearMonth.of(2020, 1), balance);
        open(UNAVAILABLE, Behaviour.UNAVAILABLE, null, balance);
        open(SLOW, Behaviour.SLOW, null, balance);
    }

    public String billingCurrency() {
        return properties.billingCurrency();
    }

    /** 0100 -> 0110. Throws {@link IssuerUnavailableException} for a card whose bank is down. */
    public AuthorizationResponse authorize(AuthorizationRequest request) {
        if (!properties.billingCurrency().equals(request.billingCurrency())) {
            throw new IllegalArgumentException("issuer bills in " + properties.billingCurrency()
                    + ", request is in " + request.billingCurrency());
        }
        Behaviour behaviour = behaviourOf(request.pan());
        if (behaviour == Behaviour.UNAVAILABLE) {
            throw new IssuerUnavailableException("issuer host for " + Iso8583.maskPan(request.pan()) + " is down");
        }
        AuthorizationResponse response = decide(request);
        if (behaviour == Behaviour.SLOW) {
            sleep(properties.slowResponse()); // decided (and held) already; only the answer is late
        }
        return response;
    }

    private synchronized AuthorizationResponse decide(AuthorizationRequest request) {
        Account account = accounts.get(request.pan());
        YearMonth thisMonth = YearMonth.now(clock);
        ResponseCode code;
        if (account.behaviour == Behaviour.DO_NOT_HONOR) {
            code = ResponseCode.DO_NOT_HONOR;
        } else if (request.expiry().isBefore(thisMonth)
                || (account.closedAfter != null && account.closedAfter.isBefore(thisMonth))) {
            code = ResponseCode.EXPIRED_CARD;
        } else if (request.billingAmountMinor() > account.availableMinor()) {
            code = ResponseCode.INSUFFICIENT_FUNDS;
        } else {
            code = ResponseCode.APPROVED;
        }
        if (!code.approved()) {
            return new AuthorizationResponse(request.stan(), request.rrn(), null, code);
        }
        if (reversed.contains(request.rrn())) {
            // the acquirer already gave up on this one and reversed it: approve into the void, hold nothing
            log.info("0100 rrn={} arrived after its reversal: no hold placed", request.rrn());
        } else {
            account.holds.put(request.rrn(), request.billingAmountMinor());
            cardOf.put(request.rrn(), request.pan());
        }
        return new AuthorizationResponse(request.stan(), request.rrn(), approvalCode(), code);
    }

    /** 0400 -> 0410. Always 00: a duplicate, or a reversal whose 0100 hasn't arrived yet, is acknowledged too. */
    public synchronized ReversalResponse reverse(ReversalRequest request) {
        String rrn = request.originalRrn();
        if (!reversed.add(rrn)) {
            log.info("0400 rrn={} is a duplicate reversal, nothing to do", rrn);
        } else {
            Account account = Optional.ofNullable(cardOf.get(rrn)).map(accounts::get).orElse(null);
            Long released = account == null ? null : account.holds.remove(rrn);
            if (released != null) {
                log.info("0400 rrn={} ({}) released hold of {} on {}", rrn, request.reason(), released,
                        Iso8583.maskPan(account.pan));
            } else {
                log.info("0400 rrn={} ({}): no hold (yet); remembered so a late 0100 can't place one", rrn, request.reason());
            }
        }
        return new ReversalResponse(request.stan(), rrn, ResponseCode.APPROVED);
    }

    /** Capture clearing: the hold becomes a debit of {@code billingAmountMinor}; the rest of the hold is released. */
    public synchronized void presentCapture(String rrn, long billingAmountMinor) {
        Account account = accountOf(rrn);
        Long held = account.holds.get(rrn);
        if (held == null || billingAmountMinor > held) {
            throw new IllegalStateException("rrn " + rrn + " holds " + held + ", cannot clear " + billingAmountMinor);
        }
        account.holds.remove(rrn);
        account.balanceMinor -= billingAmountMinor;
    }

    /** Refund clearing: the money goes back onto the card. */
    public synchronized void presentRefund(String rrn, long billingAmountMinor) {
        accountOf(rrn).balanceMinor += billingAmountMinor;
    }

    public synchronized Optional<AccountView> account(String pan) {
        return Optional.ofNullable(accounts.get(pan)).map(this::view);
    }

    public synchronized List<AccountView> accounts() {
        List<AccountView> views = new ArrayList<>();
        accounts.values().forEach(a -> views.add(view(a)));
        return views;
    }

    private AccountView view(Account account) {
        return new AccountView(Iso8583.maskPan(account.pan), properties.billingCurrency(), account.balanceMinor,
                account.heldMinor(), account.availableMinor(), account.holds.size());
    }

    /** Any card we've never seen gets an ordinary account: so every Luhn-valid card the vault accepts works. */
    private synchronized Behaviour behaviourOf(String pan) {
        return accounts.computeIfAbsent(pan, p -> new Account(p, Behaviour.NORMAL, null, properties.defaultBalanceMinor()))
                .behaviour;
    }

    private Account accountOf(String rrn) {
        String pan = cardOf.get(rrn);
        if (pan == null) {
            throw new IllegalStateException("no approved authorization with rrn " + rrn);
        }
        return accounts.get(pan);
    }

    private void open(String pan, Behaviour behaviour, YearMonth closedAfter, long balanceMinor) {
        accounts.put(pan, new Account(pan, behaviour, closedAfter, balanceMinor));
    }

    private String approvalCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return code.toString();
    }

    private static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
