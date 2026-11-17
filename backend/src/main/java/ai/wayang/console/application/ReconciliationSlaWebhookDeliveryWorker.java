package ai.wayang.console.application;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Delivers provider-owned durable webhook records without holding database locks during I/O. */
@ApplicationScoped
public class ReconciliationSlaWebhookDeliveryWorker {
    private static final Logger LOG = Logger.getLogger(ReconciliationSlaWebhookDeliveryWorker.class);
    @Inject ReconciliationSlaWebhookSink sink;
    @Inject ReconciliationSlaWebhookClient client;

    @Scheduled(every = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void deliverDue() {
        for (var claim : sink.claimDue(Instant.now(), 20)) {
            try {
                var destination = ReconciliationSlaWebhookSink.destination(claim.organizationId(), claim.environmentId());
                sink.recordSecretVersion(claim, destination.secretVersion());
                var transition = new DeploymentReconciliationService.SlaAlertTransition(
                        claim.organizationId(), claim.environmentId(), claim.deploymentId(),
                        claim.fromStatus(), claim.toStatus(), claim.occurredAt(),
                        "console:reconciliation-webhook", claim.auditSequence(), claim.recordHash());
                var response = client.deliver(destination.endpoint(), destination.secretReference(), destination.secretVersion(), transition);
                sink.complete(claim, response);
            } catch (RuntimeException failure) {
                // Do not log exception messages: transport/provider failures may contain URLs or secrets.
                String code = failure instanceof IllegalArgumentException
                        ? "INVALID_DESTINATION" : "WEBHOOK_DELIVERY_ERROR";
                LOG.warnf("SLA webhook delivery failed for environment %s with code %s",
                        claim.environmentId(), code);
                sink.fail(claim, code);
            }
        }
    }
    /** Prunes only old terminal provider rows; pending and leased work is never eligible. */
    @Scheduled(every = "24h", delayed = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void purgeExpiredTerminalDeliveries() {
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        int retentionDays = config.getOptionalValue(
                "wayang.console.reconciliation.sla.webhook.retention-days", Integer.class).orElse(90);
        int batchSize = config.getOptionalValue(
                "wayang.console.reconciliation.sla.webhook.retention-batch-size", Integer.class).orElse(500);
        if (retentionDays < 7 || retentionDays > 3650 || batchSize < 1 || batchSize > 1000) {
            LOG.error("SLA webhook retention configuration is invalid; cleanup is skipped");
            return;
        }
        int removed = sink.purgeTerminalBefore(Instant.now().minusSeconds(retentionDays * 86400L), batchSize);
        if (removed > 0) LOG.infof("Pruned %d expired terminal SLA webhook delivery records", removed);
    }
}

