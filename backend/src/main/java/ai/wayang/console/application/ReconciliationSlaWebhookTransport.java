package ai.wayang.console.application;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Controlled outbound HTTPS transport required by webhook provider extensions.
 *
 * <p>Implementations must enforce TLS certificate and hostname verification, disable redirects,
 * apply network egress restrictions after DNS resolution (including protection against private,
 * loopback, link-local, and rebinding destinations), bound response consumption, and honor the
 * supplied timeout. A plain unrestricted HTTP client is not a compliant implementation.</p>
 */
public interface ReconciliationSlaWebhookTransport {
    Response post(URI endpoint, Map<String, String> headers, byte[] body, Duration timeout);

    record Response(int statusCode) {
        public boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }

        public boolean retryable() {
            return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
        }
    }
}
