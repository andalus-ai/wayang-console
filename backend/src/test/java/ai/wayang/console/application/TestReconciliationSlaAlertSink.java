package ai.wayang.console.application;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory provider used only by integration tests to exercise the durable sink contract. */
@ApplicationScoped
public class TestReconciliationSlaAlertSink implements ReconciliationSlaAlertSink {
    private record Scope(UUID organizationId, UUID environmentId) {}
    private final Map<Scope, Long> cursors = new ConcurrentHashMap<>();
    private final Map<String, DeploymentReconciliationService.SlaAlertTransition> accepted = new ConcurrentHashMap<>();

    @Override public String sinkId() { return "test-memory"; }

    @Override
    public long lastAcceptedSequence(UUID organizationId, UUID environmentId) {
        return cursors.getOrDefault(new Scope(organizationId, environmentId), 0L);
    }

    @Override
    public synchronized void accept(DeploymentReconciliationService.SlaAlertTransition transition,
            long expectedLastAcceptedSequence) {
        Scope scope = new Scope(transition.organizationId(), transition.environmentId());
        long current = cursors.getOrDefault(scope, 0L);
        String key = transition.organizationId() + ":" + transition.environmentId() + ":"
                + transition.auditSequence() + ":" + transition.recordHash();
        if (transition.auditSequence() <= current) return;
        if (current != expectedLastAcceptedSequence)
            throw new IllegalStateException("Provider cursor changed concurrently");
        accepted.putIfAbsent(key, transition);
        cursors.put(scope, transition.auditSequence());
    }

    @Override
    public ReconciliationSlaAlertSink.DeliveryStatus status(UUID organizationId, Set<UUID> environments) {
        long count = accepted.values().stream().filter(event -> event.organizationId().equals(organizationId)
                && environments.contains(event.environmentId())).count();
        return new ReconciliationSlaAlertSink.DeliveryStatus(count, 0, count, 0,
                null, count == 0 ? null : Instant.now(), null, null, "HEALTHY");
    }
}
