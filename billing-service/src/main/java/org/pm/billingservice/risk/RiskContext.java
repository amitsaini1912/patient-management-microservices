package org.pm.billingservice.risk;

import java.util.regex.Pattern;

/**
 * What billing knows about the CLIENT of a payment request, for the risk engine: its IP and a device id.
 * Built from the HTTP request; anything malformed becomes "unknown" rather than failing the payment (the engine
 * would answer 422 and we'd fall back anyway).
 */
public record RiskContext(String clientIp, String deviceId) {

    public static final String UNKNOWN_IP = "0.0.0.0";
    public static final String UNKNOWN_DEVICE = "unknown";
    public static final String DEVICE_HEADER = "X-Device-Id";

    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern DEVICE = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");

    public static RiskContext none() {
        return new RiskContext(UNKNOWN_IP, UNKNOWN_DEVICE);
    }

    /**
     * @param forwardedFor X-Forwarded-For (api-gateway adds it; the first entry is the original client)
     * @param remoteAddr   the TCP peer, used when there is no X-Forwarded-For
     * @param deviceId     X-Device-Id from the client app, optional
     */
    public static RiskContext of(String forwardedFor, String remoteAddr, String deviceId) {
        String ip = forwardedFor != null && !forwardedFor.isBlank() ? forwardedFor.split(",")[0].trim() : remoteAddr;
        return new RiskContext(validIp(ip) ? ip : UNKNOWN_IP,
                deviceId != null && DEVICE.matcher(deviceId.trim()).matches() ? deviceId.trim() : UNKNOWN_DEVICE);
    }

    private static boolean validIp(String ip) {
        // a syntax check only (InetAddress.getByName would do a DNS lookup for a host name)
        return ip != null && (IPV4.matcher(ip).matches() || (ip.contains(":") && IPV6.matcher(ip).matches()));
    }
}
