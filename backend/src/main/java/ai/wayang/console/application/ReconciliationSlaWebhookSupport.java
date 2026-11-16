package ai.wayang.console.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless validation and signing primitives shared by webhook provider extensions. */
public final class ReconciliationSlaWebhookSupport {
    private static final String HMAC_SHA256 = "HmacSHA256";

    private ReconciliationSlaWebhookSupport() {}

    /**
     * Accepts only HTTPS endpoints whose DNS hostname exactly matches a deployment allowlist.
     * IP literals, user-info, query strings, fragments, non-standard ports, and local hostnames
     * are rejected. The transport must additionally enforce resolved-IP egress policy.
     */
    public static URI validateEndpoint(URI endpoint, Set<String> allowedHosts) {
        if (endpoint == null || allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("Webhook endpoint and host allowlist are required");
        }
        String host = endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || host == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw new IllegalArgumentException("Webhook endpoint must be a valid HTTPS URL");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (isIpLiteral(normalizedHost) || normalizedHost.equals("localhost")
                || normalizedHost.endsWith(".localhost") || normalizedHost.endsWith(".local")
                || normalizedHost.endsWith(".internal")) {
            throw new IllegalArgumentException("Webhook endpoint host is not permitted");
        }

        boolean allowed = allowedHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .anyMatch(normalizedHost::equals);
        if (!allowed) {
            throw new IllegalArgumentException("Webhook endpoint host is not allowlisted");
        }
        return endpoint;
    }

    /** Signs the exact transmitted bytes using HMAC-SHA256 over {@code timestamp + "." + body}. */
    public static String signature(char[] secret, long timestampEpochSeconds, byte[] body) {
        if (secret == null || secret.length < 32 || body == null) {
            throw new IllegalArgumentException("Webhook signing secret must contain at least 32 characters");
        }
        java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(secret));
        byte[] key = new byte[encoded.remaining()];
        encoded.get(key);
        byte[] prefix = (timestampEpochSeconds + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(signed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            java.util.Arrays.fill(signed, (byte) 0);
        }
    }

    /** Stable key for receiver-side deduplication across retries and Console restarts. */
    public static String idempotencyKey(DeploymentReconciliationService.SlaAlertTransition transition) {
        if (transition == null || transition.recordHash() == null || transition.auditSequence() <= 0) {
            throw new IllegalArgumentException("A persisted audit-backed transition is required");
        }
        String value = transition.organizationId() + ":" + transition.environmentId() + ":"
                + transition.auditSequence() + ":" + transition.recordHash();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isIpLiteral(String host) {
        // URI#getHost returns bracketed IPv6 literals on some JDKs and bare IPv6 on others.
        String value = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return value.indexOf(':') >= 0 || value.matches("[0-9.]+");
    }
}
