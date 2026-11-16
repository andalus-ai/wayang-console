package ai.wayang.console.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Builds and signs an audit-backed SLA webhook request.
 *
 * <p>This class deliberately delegates secret resolution and network egress to mandatory
 * extension SPIs. It does not persist delivery state or retry requests: the
 * {@link ReconciliationSlaAlertSink} provider remains responsible for durable acceptance,
 * retry/backoff, cooldown, and terminal outcomes.</p>
 */
@ApplicationScoped
public class ReconciliationSlaWebhookClient {
    @Inject ObjectMapper objectMapper;
    @Inject Instance<ReconciliationSlaWebhookSecretResolver> secretResolvers;
    @Inject Instance<ReconciliationSlaWebhookTransport> transports;

    @ConfigProperty(name = "wayang.console.reconciliation.sla.webhook.allowed-hosts", defaultValue = "")
    Set<String> allowedHosts;

    @ConfigProperty(name = "wayang.console.reconciliation.sla.webhook.timeout-seconds", defaultValue = "5")
    long timeoutSeconds;

    public ReconciliationSlaWebhookTransport.Response deliver(
            URI endpoint, String secretReference,
            DeploymentReconciliationService.SlaAlertTransition transition) {
        URI safeEndpoint = ReconciliationSlaWebhookSupport.validateEndpoint(endpoint, allowedHosts);
        if (secretReference == null || !secretReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) {
            throw new IllegalArgumentException("A valid external webhook secret reference is required");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalStateException("Webhook timeout must be between 1 and 30 seconds");
        }
        if (!secretResolvers.isResolvable() || !transports.isResolvable()) {
            throw new IllegalStateException("A secure webhook secret resolver and egress transport are required");
        }

        byte[] body = serialize(transition);
        long timestamp = Instant.now().getEpochSecond();
        char[] secret = secretResolvers.get().resolve(secretReference);
        if (secret == null) {
            throw new IllegalStateException("Webhook secret reference could not be resolved");
        }
        try {
            String signature = ReconciliationSlaWebhookSupport.signature(secret, timestamp, body);
            Map<String, String> headers = Map.of(
                    "Content-Type", "application/json",
                    "X-Wayang-Webhook-Timestamp", Long.toString(timestamp),
                    "X-Wayang-Webhook-Signature", signature,
                    "Idempotency-Key", ReconciliationSlaWebhookSupport.idempotencyKey(transition));
            return transports.get().post(safeEndpoint, headers, body, Duration.ofSeconds(timeoutSeconds));
        } finally {
            java.util.Arrays.fill(secret, '\0');
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private byte[] serialize(DeploymentReconciliationService.SlaAlertTransition transition) {
        if (transition == null || transition.auditSequence() <= 0 || transition.recordHash() == null) {
            throw new IllegalArgumentException("A persisted audit-backed transition is required");
        }
        var payload = new WebhookPayload(
                "wayang.reconciliation.sla.transition.v1",
                transition.organizationId().toString(),
                transition.environmentId().toString(),
                transition.deploymentId().toString(),
                transition.fromStatus(),
                transition.toStatus(),
                transition.occurredAt().toString(),
                transition.auditSequence(),
                transition.recordHash());
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize SLA webhook payload", e);
        }
    }

    private record WebhookPayload(String type, String organizationId, String environmentId,
            String deploymentId, String fromStatus, String toStatus, String occurredAt,
            long auditSequence, String recordHash) {}
}
