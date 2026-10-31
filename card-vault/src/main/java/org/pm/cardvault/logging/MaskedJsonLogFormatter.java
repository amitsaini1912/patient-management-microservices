package org.pm.cardvault.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * One JSON object per log line (Day 20b, ADR 0020), same field names as the other services' ECS logs, with every
 * text that could carry a PAN - message, exception messages, stack trace - masked by {@link PanMasker} (ADR 0013).
 * Boot's own ECS formatter can't mask, so card-vault uses this one, always (logback-spring.xml).
 * MDC entries are added as fields: with the OpenTelemetry agent that includes trace_id and span_id.
 */
public class MaskedJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("log.level", event.getLevel().toString());
        line.put("process.thread.name", event.getThreadName());
        line.put("service.name", "card-vault");
        line.put("log.logger", event.getLoggerName());
        line.put("message", PanMasker.mask(event.getFormattedMessage()));
        event.getMDCPropertyMap().forEach((key, value) -> line.put(key, PanMasker.mask(value)));
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            line.put("error.type", error.getClassName());
            line.put("error.message", PanMasker.mask(String.valueOf(error.getMessage())));
            line.put("error.stack_trace", PanMasker.mask(stackTrace(error)));
        }
        try {
            return JSON.writeValueAsString(line) + "\n";
        } catch (JsonProcessingException e) {
            return "{\"message\":\"log line could not be written as JSON\"}\n";
        }
    }

    private static String stackTrace(IThrowableProxy error) {
        StringBuilder text = new StringBuilder();
        for (IThrowableProxy t = error; t != null; t = t.getCause()) {
            text.append(t == error ? "" : "Caused by: ").append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            for (StackTraceElementProxy frame : t.getStackTraceElementProxyArray()) {
                text.append('\t').append(frame.getSTEAsString()).append('\n');   // "at ..." already
            }
        }
        return text.toString();
    }
}
