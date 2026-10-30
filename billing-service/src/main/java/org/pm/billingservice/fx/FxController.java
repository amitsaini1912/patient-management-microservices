package org.pm.billingservice.fx;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pm.billingservice.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 *   POST /fx/rates                      {"base":"USD","quote":"INR","rate":"84.10","source":"manual"}
 *   GET  /fx/rates/{base}/{quote}       the rate in force now
 *   POST /fx/revaluations               mark open foreign positions to the latest rates (also daily, 01:00 UTC)
 * Internal ops endpoints (no api-gateway route yet, like /reconciliation).
 */
@RestController
@RequestMapping("/fx")
public class FxController {

    private final FxRateService rates;
    private final FxRevaluationService revaluation;

    public FxController(FxRateService rates, FxRevaluationService revaluation) {
        this.rates = rates;
        this.revaluation = revaluation;
    }

    public record NewRate(@NotBlank @Pattern(regexp = "[A-Z]{3}") String base,
                          @NotBlank @Pattern(regexp = "[A-Z]{3}") String quote,
                          // a string, like amounts: "84.10" must not pass through a double
                          @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?") String rate,
                          @NotNull String source,
                          Instant asOf) {}

    public record RateView(String base, String quote, String rate, String source, Instant asOf) {
        static RateView of(FxRate fx) {
            return new RateView(fx.base().getCurrencyCode(), fx.quote().getCurrencyCode(), fx.rate().stripTrailingZeros().toPlainString(),
                    fx.source(), fx.asOf());
        }
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public RateView record(@Valid @RequestBody NewRate request) {
        return RateView.of(rates.record(Currency.getInstance(request.base()), Currency.getInstance(request.quote()),
                new BigDecimal(request.rate()), request.source(), request.asOf()));
    }

    @GetMapping("/rates/{base}/{quote}")
    public RateView current(@PathVariable String base, @PathVariable String quote) {
        return RateView.of(rates.current(Currency.getInstance(base), Currency.getInstance(quote)));
    }

    /** Amounts as decimal strings with their currency, like the payment API ("50.00 USD"). */
    public record RevaluationView(Map<String, String> positions, String carried, String worthNow, String delta) {
        static RevaluationView of(FxRevaluationService.Result r) {
            Map<String, String> positions = new LinkedHashMap<>();
            r.positions().forEach((currency, money) -> positions.put(currency, text(money)));
            return new RevaluationView(positions, text(r.carried()), text(r.worthNow()), text(r.delta()));
        }

        private static String text(Money money) {
            return money.toDecimal().toPlainString() + " " + money.currency().getCurrencyCode();
        }
    }

    @PostMapping("/revaluations")
    public RevaluationView revalue() {
        return RevaluationView.of(revaluation.revalue());
    }

    /** Daily, before the 02:00 reconciliation: books close with positions at the day's rates. */
    @Scheduled(cron = "${billing.fx.revaluation-cron:0 0 1 * * *}", zone = "UTC")
    void scheduledRevaluation() {
        revaluation.revalue();
    }
}
