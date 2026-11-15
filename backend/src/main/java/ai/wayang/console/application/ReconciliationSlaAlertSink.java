package ai.wayang.console.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Durable alert-delivery extension contract.
 *
 * accept() must atomically compare the current cursor with expectedLastAcceptedSequence,
 * persist the transition, and advance the cursor for its organization/environment. A
 * repeated already-accepted transition is an idempotent success; a cursor conflict must
 * be rejected so replay can restart from the provider's latest cursor. It must be idempotent on (organizationId,
 * environmentId, auditSequence, recordHash). Provider implementations own their
 * durable delivery state, retry/backoff, cooldown, and terminal failure handling.
 * The core never holds a database transaction while invoking this interface.
 */
public interface ReconciliationSlaAlertSink {
    String sinkId();

    long lastAcceptedSequence(UUID organizationId, UUID environmentId);

    void accept(DeploymentReconciliationService.SlaAlertTransition transition, long expectedLastAcceptedSequence);

    DeliveryStatus status(UUID organizationId, Set<UUID> environments);

    record DeliveryStatus(long accepted, long pending, long delivered, long failed,
            Instant oldestPendingAt, Instant lastDeliveredAt, Instant lastFailureAt,
            String lastFailureCode, String health) {}
}
