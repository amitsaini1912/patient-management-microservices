package org.pm.billingservice.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Carries the W3C trace context ({@code traceparent} / {@code tracestate}) across the outbox (ADR 0019).
 *
 * Only the OpenTelemetry API: with the Java agent attached it sees the agent's spans; without it, Context.current()
 * has no span, nothing is stored and nothing changes. The W3C propagator is used directly (not the global one), so
 * this works the same in tests that create their own tracer.
 */
final class TraceContext {

    static final String TRACEPARENT = "traceparent";
    static final String TRACESTATE = "tracestate";

    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private TraceContext() {}

    /** traceparent (+ tracestate) of the current span; empty when there is none (tracing off). */
    static Map<String, String> current() {
        Map<String, String> carrier = new HashMap<>();
        W3C.inject(Context.current(), carrier, Map::put);
        return carrier;
    }

    /**
     * A parent that is NOT sampled: with the agent's default parent-based sampler nothing under it is recorded.
     * The relay polls every 0.5 s; without this each poll (and its SELECT) would be a trace of its own and bury the
     * payment traces in Jaeger (found by hand on Day 20a: 374 poll traces in 20 minutes).
     */
    static Context unsampled() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String traceId = HexFormat.of().toHexDigits(random.nextLong() | 1) + HexFormat.of().toHexDigits(random.nextLong());
        String spanId = HexFormat.of().toHexDigits(random.nextLong() | 1);
        return Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault()))
                .storeInContext(Context.root());
    }

    /** The stored context as a parent; Context.root() when nothing was stored. */
    static Context parent(String traceparent, String tracestate) {
        if (traceparent == null) {
            return Context.root();
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, traceparent);
        if (tracestate != null) {
            carrier.put(TRACESTATE, tracestate);
        }
        return W3C.extract(Context.root(), carrier, GETTER);
    }
}
