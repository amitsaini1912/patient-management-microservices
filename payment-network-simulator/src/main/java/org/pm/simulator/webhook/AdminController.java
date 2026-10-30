package org.pm.simulator.webhook;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test knobs (a real PSP has none of these): make webhooks arrive twice, or hold them and release them out of order.
 *   PUT  /admin/webhooks/mode        {"mode": "NORMAL" | "DUPLICATE" | "HOLD"}
 *   POST /admin/webhooks/flush?reversed=true
 *   POST /admin/webhooks/{eventId}/resend
 *   GET  /admin/webhooks             delivery log
 */
@RestController
@RequestMapping("/admin/webhooks")
public class AdminController {

    private final WebhookDispatcher dispatcher;

    public AdminController(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public record ModeRequest(WebhookMode mode) {}

    @PutMapping("/mode")
    public Map<String, Object> setMode(@RequestBody ModeRequest request) {
        dispatcher.setMode(request.mode());
        return Map.of("mode", dispatcher.mode());
    }

    @PostMapping("/flush")
    public Map<String, Object> flush(@RequestParam(defaultValue = "false") boolean reversed) {
        return Map.of("flushed", dispatcher.flush(reversed));
    }

    @PostMapping("/{eventId}/resend")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable String eventId) {
        return dispatcher.resend(eventId)
                ? ResponseEntity.accepted().body(Map.of("resent", eventId))
                : ResponseEntity.notFound().build();
    }

    @GetMapping
    public List<WebhookDispatcher.Delivery> deliveries() {
        return dispatcher.deliveries();
    }
}
