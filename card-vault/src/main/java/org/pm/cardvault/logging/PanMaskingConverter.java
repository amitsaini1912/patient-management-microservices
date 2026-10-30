package org.pm.cardvault.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.CoreConstants;

/**
 * Logback {@code %maskedMsg}: the formatted message plus the stack trace (exception messages included),
 * with PANs masked. Defence in depth: the code shouldn't log PANs at all; this catches the mistakes
 * (a library's exception message quoting input, a toString someone forgot).
 */
public class PanMaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(CoreConstants.LINE_SEPARATOR).append(t.getClassName()).append(": ").append(t.getMessage());
            for (StackTraceElementProxy frame : t.getStackTraceElementProxyArray()) {
                text.append(CoreConstants.LINE_SEPARATOR).append("\tat ").append(frame.getSTEAsString());
            }
        }
        return PanMasker.mask(text.toString());
    }
}
