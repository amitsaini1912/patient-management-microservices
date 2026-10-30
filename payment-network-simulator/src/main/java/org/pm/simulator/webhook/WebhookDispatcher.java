package org.pm.simulator.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.pm.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Delivers webhooks asynchronously, signed, with retries: any non-2xx or network error is retried
 * (1s, 2s, 4s ...) up to max-attempts. Like real PSPs, no ordering promise: two events may arrive in either order.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final SimulatorProperties.Webhooks config;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private volatile WebhookMode mode = WebhookMode.NORMAL;
    private final List<WebhookEvent> held = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();

    public WebhookDispatcher(SimulatorProperties properties, ObjectMapper objectMapper) {
        this.config = properties.webhooks();
        if (config == null || config.secret() == null || config.secret().isBlank() || config.targetUrl() == null) {
            throw new IllegalStateException("simulator.webhooks.secret and target-url must be set");
        }
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public WebhookEvent publish(String type, Map<String, Object> data) {
        WebhookEvent event = new WebhookEvent("evt_" + UUID.randomUUID(), type, clock.instant().getEpochSecond(), data);
        switch (mode) {
            case HOLD -> held.add(event);
            case DUPLICATE -> {
                schedule(event, 1, config.delay());
                schedule(event, 1, config.delay().plusMillis(50));
            }
            case NORMAL -> schedule(event, 1, config.delay());
        }
        return event;
    }

    /** Send everything held, back to back (reversed = newest first = out of order). */
    public int flush(boolean reversed) {
        List<WebhookEvent> batch;
        synchronized (held) {
            batch = new ArrayList<>(held);
            held.clear();
        }
        if (reversed) {
            Collections.reverse(batch);
        }
        long gapMs = 0;
        for (WebhookEvent event : batch) {
            schedule(event, 1, Duration.ofMillis(gapMs)); // a small gap keeps the chosen order on the wire
            gapMs += 100;
        }
        return batch.size();
    }

    /** Send an already-sent event again (same id): "the PSP delivered it twice". */
    public boolean resend(String eventId) {
        Delivery delivery = deliveries.get(eventId);
        if (delivery == null) {
            return false;
        }
        schedule(delivery.event(), 1, Duration.ZERO);
        return true;
    }

    public void setMode(WebhookMode mode) {
        this.mode = mode;
    }

    public WebhookMode mode() {
        return mode;
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries.values());
    }

    private void schedule(WebhookEvent event, int attempt, Duration delay) {
        deliveries.putIfAbsent(event.id(), new Delivery(event, 0, null, false));
        scheduler.schedule(() -> attempt(event, attempt), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void attempt(WebhookEvent event, int attempt) {
        Integer status = null;
        try {
            String body = objectMapper.writeValueAsString(event);
            long timestamp = clock.instant().getEpochSecond(); // signed at send time, so retries get a fresh one
            HttpRequest request = HttpRequest.newBuilder(config.targetUrl())
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(WebhookSigner.HEADER, WebhookSigner.header(config.secret(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("webhook {} ({}) attempt {} failed: {}", event.id(), event.type(), attempt, e.toString());
        }

        Integer finalStatus = status;
        boolean ok = status != null && status >= 200 && status < 300;
        deliveries.merge(event.id(), new Delivery(event, 1, status, ok),
                (old, now) -> new Delivery(event, old.attempts() + 1, finalStatus, old.delivered() || ok));
        if (ok) {
            log.info("webhook {} ({}) delivered, HTTP {}", event.id(), event.type(), status);
        } else if (attempt < config.maxAttempts()) {
            Duration backoff = config.initialBackoff().multipliedBy(1L << (attempt - 1));
            log.warn("webhook {} ({}) got HTTP {}, retry {} in {}", event.id(), event.type(), status, attempt + 1, backoff);
            scheduler.schedule(() -> attempt(event, attempt + 1), backoff.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            log.error("webhook {} ({}) given up after {} attempts", event.id(), event.type(), attempt);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** One event's delivery state, for GET /admin/webhooks. */
    public record Delivery(WebhookEvent event, int attempts, Integer lastStatus, boolean delivered) {}
}
