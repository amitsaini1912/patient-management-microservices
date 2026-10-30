package org.pm.simulator.webhook;

/** Test knob: how the dispatcher misbehaves (real PSPs do all of these now and then). */
public enum WebhookMode {
    /** deliver each event once (plus retries on failure) */
    NORMAL,
    /** deliver each event twice, same event id */
    DUPLICATE,
    /** don't deliver; queue until {@code POST /admin/webhooks/flush} (optionally in reverse = out of order) */
    HOLD
}
