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
                var transition = new DeploymentReconciliationService.SlaAlertTransition(
                        claim.organizationId(), claim.environmentId(), claim.deploymentId(),
                        claim.fromStatus(), claim.toStatus(), claim.occurredAt(),
                        "console:reconciliation-webhook", claim.auditSequence(), claim.recordHash());
                var response = client.deliver(destination.endpoint(), destination.secretReference(), transition);
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
}
