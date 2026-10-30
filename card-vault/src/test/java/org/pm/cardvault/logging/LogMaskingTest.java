package org.pm.cardvault.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pm.cardvault.TestcontainersConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "PAN never logged": drive every path (success, refusals, bad input, errors) and also log PANs ON PURPOSE, the way a
 * careless line or a library would, then scan EVERYTHING written to stdout/stderr for any 13-19 digit Luhn-valid run.
 * The scanner here is written independently of PanMasker, so a bug in the masker can't hide itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingTest {

    private static final Logger careless = LoggerFactory.getLogger("careless.code");
    private static final String[] PANS = {"4111111111111111", "5555555555554444", "378282246310005", "6011111111111117"};

    @Autowired private MockMvc mvc;

    @Test
    void noLuhnValidCardNumberEverReachesTheLogs(CapturedOutput output) throws Exception {
        for (String pan : PANS) {
            String body = mvc.perform(post("/cards").header("X-Api-Key", "test-gateway-key-0123456789")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pan\":\"" + pan + "\",\"expiry_month\":12,\"expiry_year\":2030}"))
                    .andReturn().getResponse().getContentAsString();
            String token = body.replaceAll(".*\"token\":\"(tok_[a-z2-7]+)\".*", "$1");
            mvc.perform(post("/cards/" + token + "/detokenize").header("X-Api-Key", "test-simulator-key-0123456789"));
            mvc.perform(post("/cards/" + token + "/detokenize").header("X-Api-Key", "test-billing-key-0123456789"));
            mvc.perform(get("/cards/" + token).header("X-Api-Key", "test-billing-key-0123456789"));
            // bad input carrying the PAN in odd places
            mvc.perform(post("/cards").header("X-Api-Key", "test-gateway-key-0123456789").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pan\":\"" + pan + "\",\"expiry_month\":\"" + pan + "\",\"expiry_year\":2030}"));
            mvc.perform(post("/cards").header("X-Api-Key", "test-gateway-key-0123456789").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pan\":\"" + pan + "\",\"expiry_month\":12,\"expiry_year\":2001}"));
            mvc.perform(post("/cards").header("X-Api-Key", "test-gateway-key-0123456789").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pan\":\"" + pan + " broken json"));

            // the mistakes the masking layout exists for
            careless.info("debugging card {}", pan);
            careless.warn("formatted: " + pan.substring(0, 4) + " " + pan.substring(4, 8) + " " + pan.substring(8));
            careless.error("failed", new IllegalStateException("charge failed for card " + pan,
                    new RuntimeException("root cause mentions " + pan)));
        }

        List<String> leaks = luhnValidRuns(output.getAll());
        assertThat(leaks).as("card numbers in the log output").isEmpty();
        assertThat(output.getAll()).contains("[PAN ****1111]"); // proof the careless lines were logged, masked
    }

    /**
     * Independent scanner: ANY standalone 13-19 digit number (single spaces/dashes allowed between digits) that
     * passes Luhn - not just the PANs used above. "Standalone" = not glued to more digits: on Day 10 an earlier
     * version also checked every 13-19 window INSIDE longer digit runs and flaked ~3% of runs on Testcontainers'
     * 64-hex container ids (captured when this class starts the Spring context); a digit window inside an id
     * is not a card number.
     */
    static List<String> luhnValidRuns(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\d])\\d(?:[ -]?\\d){12,18}(?![\\d])").matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (luhn(digits)) {
                found.add(digits);
            }
        }
        return found;
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }
}
