package org.pm.cardvault.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Day 20b: one JSON object per line, ECS-style names, MDC (trace ids) included, PANs masked everywhere. */
class MaskedJsonLogFormatterTest {

    private final LoggerContext context = new LoggerContext();

    private JsonNode format(String message, Object[] args, Throwable error, Map<String, String> mdc) throws Exception {
        LoggingEvent event = new LoggingEvent("test", context.getLogger("careless.code"), Level.WARN, message, error, args);
        event.setMDCPropertyMap(mdc);
        String line = new MaskedJsonLogFormatter().format(event);
        assertThat(line).endsWith("\n").doesNotContain("\n{");                     // exactly one line
        return new ObjectMapper().readTree(line);
    }

    @Test
    void fieldsAndTraceIds() throws Exception {
        JsonNode line = format("tokenized {}", new Object[] {"tok_abc"}, null,
                Map.of("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736", "span_id", "00f067aa0ba902b7"));
        assertThat(line.get("message").asText()).isEqualTo("tokenized tok_abc");
        assertThat(line.get("log.level").asText()).isEqualTo("WARN");
        assertThat(line.get("log.logger").asText()).isEqualTo("careless.code");
        assertThat(line.get("service.name").asText()).isEqualTo("card-vault");
        assertThat(line.get("trace_id").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(line.get("span_id").asText()).isEqualTo("00f067aa0ba902b7");
        assertThat(line.has("@timestamp")).isTrue();
        assertThat(line.has("error.type")).isFalse();
    }

    @Test
    void panIsMaskedInMessageMdcErrorAndStackTrace() throws Exception {
        Throwable error = new IllegalStateException("charge failed for 4111111111111111",
                new RuntimeException("cause mentions 5555 5555 5555 4444"));
        JsonNode line = format("card {}", new Object[] {"4111111111111111"}, error, Map.of("debug", "378282246310005"));
        String all = line.toString();
        assertThat(all).doesNotContain("4111111111111111", "5555 5555 5555 4444", "378282246310005");
        assertThat(line.get("message").asText()).isEqualTo("card [PAN ****1111]");
        assertThat(line.get("error.message").asText()).isEqualTo("charge failed for [PAN ****1111]");
        assertThat(line.get("error.stack_trace").asText()).contains("Caused by: java.lang.RuntimeException", "[PAN ****4444]")
                .contains("\tat org.pm.cardvault.logging.MaskedJsonLogFormatterTest").doesNotContain("at at ");
        assertThat(line.get("debug").asText()).isEqualTo("[PAN ****0005]");
    }
}
