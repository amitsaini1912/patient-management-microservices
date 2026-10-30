package org.pm.cardvault.card;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import org.pm.cardvault.crypto.CardCipher;
import org.pm.cardvault.security.ApiKeyFilter;
import org.pm.cardvault.security.ForbiddenException;
import org.pm.cardvault.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 *   POST /cards                      TOKENIZE        {"pan": "...", "expiry_month": 12, "expiry_year": 2030} -> metadata + token
 *   GET  /cards/{token}              READ_METADATA   -> metadata (no PAN)
 *   POST /cards/{token}/detokenize   DETOKENIZE      -> PAN (POST: the response carries the PAN, and a GET is
 *                                                       too easily cached, prefetched or logged with its URL)
 * No bean validation on the PAN on purpose: its error messages echo the rejected value.
 */
@RestController
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenizeRequest(String pan, int expiryMonth, int expiryYear) {
        @Override
        public String toString() {
            return "TokenizeRequest{pan=<redacted>, expiry=" + expiryMonth + "/" + expiryYear + "}";
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CardView(String token, String last4, CardBrand brand, String fingerprint, int expiryMonth, int expiryYear) {
        static CardView of(CardMetadata c) {
            return new CardView(c.token(), c.last4(), c.brand(), c.fingerprint(), c.expiryMonth(), c.expiryYear());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PanView(String token, String pan, int expiryMonth, int expiryYear) {
        @Override
        public String toString() {
            return "PanView{token=" + token + ", pan=<redacted>}";
        }
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardView tokenize(@RequestBody TokenizeRequest request, HttpServletRequest http) {
        require(http, Permission.TOKENIZE);
        return CardView.of(cards.tokenize(request.pan(), request.expiryMonth(), request.expiryYear()));
    }

    @GetMapping("/cards/{token}")
    public CardView metadata(@PathVariable String token, HttpServletRequest http) {
        require(http, Permission.READ_METADATA);
        return CardView.of(cards.metadata(token));
    }

    @PostMapping("/cards/{token}/detokenize")
    public PanView detokenize(@PathVariable String token, HttpServletRequest http) {
        String caller = require(http, Permission.DETOKENIZE);
        CardService.CardDetails card = cards.detokenize(token, caller);
        return new PanView(token, card.pan(), card.expiryMonth(), card.expiryYear());
    }

    private static String require(HttpServletRequest http, Permission permission) {
        ApiKeyFilter.Caller caller = (ApiKeyFilter.Caller) http.getAttribute(ApiKeyFilter.CALLER);
        if (!caller.client().permissions().contains(permission)) {
            throw new ForbiddenException(caller.name(), permission);
        }
        return caller.name();
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) {
        log.warn("refused: {}", e.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(InvalidCardException.class)
    ProblemDetail invalid(InvalidCardException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid card", e.getMessage());
    }

    @ExceptionHandler(CardNotFoundException.class)
    ProblemDetail notFound(CardNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Card not found", e.getMessage());
    }

    /** Jackson's message can quote the input (e.g. a PAN sent as expiry_month): never pass it on. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(CardCipher.TamperedCiphertextException.class)
    ProblemDetail tampered(CardCipher.TamperedCiphertextException e) {
        log.error("card data failed integrity check: {}", e.getMessage());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Card data unreadable", "stored card data failed its integrity check");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
