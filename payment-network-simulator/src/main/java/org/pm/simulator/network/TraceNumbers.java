package org.pm.simulator.network;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * STAN (field 11): 6 digits, a new one for every message we send (0100 and 0400 alike), wrapping at 999999.
 * RRN (field 37): 12 characters that name the transaction end to end: YDDDhh + the 0100's STAN.
 */
@Component
public class TraceNumbers {

    private final AtomicInteger stan = new AtomicInteger();
    private final Clock clock = Clock.systemUTC();

    public String nextStan() {
        return "%06d".formatted(stan.updateAndGet(n -> n >= 999_999 ? 1 : n + 1));
    }

    public String rrn(String stan) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return "%d%03d%02d%s".formatted(now.getYear() % 10, now.getDayOfYear(), now.getHour(), stan);
    }
}
