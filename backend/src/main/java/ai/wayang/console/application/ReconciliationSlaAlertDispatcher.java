package ai.wayang.console.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replays durable alert-intent audit records into provider-owned durable delivery stores.
 * Provider acceptance must be idempotent and atomically advance its per-scope cursor.
 */
@ApplicationScoped
public class ReconciliationSlaAlertDispatcher {
    private static final Logger LOG = Logger.getLogger(ReconciliationSlaAlertDispatcher.class);
    private static final int BATCH_SIZE = 50;

    @Inject Instance<ReconciliationSlaAlertSink> sinks;
    @Inject ReconciliationSlaAlertEventReader reader;

    public record AlertSinkStatus(String sinkId, long accepted, long pending, long delivered, long failed,
            java.time.Instant oldestPendingAt, java.time.Instant lastDeliveredAt,
            java.time.Instant lastFailureAt, String lastFailureCode, String health) {}

    /** Returns the number of durable alert intents accepted by all configured sinks. */
    public int replayPendingTransitions() {
        if (sinks.isUnsatisfied()) return 0;
        List<ReconciliationSlaAlertEventReader.AlertScope> scopes = reader.scopes();
        int accepted = 0;
        for (ReconciliationSlaAlertSink sink : sinks) {
            for (var scope : scopes) {
                long cursor;
                try {
                    cursor = sink.lastAcceptedSequence(scope.organizationId(), scope.environmentId());
                    List<DeploymentReconciliationService.SlaAlertTransition> batch =
                            reader.after(scope.organizationId(), scope.environmentId(), cursor, BATCH_SIZE);
                    for (var transition : batch) {
                        try {
                            sink.accept(transition, cursor);
                            cursor = transition.auditSequence();
                            accepted++;
                        } catch (RuntimeException failure) {
                            LOG.errorf(failure, "SLA alert sink %s rejected audit sequence %d in environment %s",
                                    sink.sinkId(), transition.auditSequence(), scope.environmentId());
                            break;
                        }
                    }
                } catch (RuntimeException failure) {
                    LOG.errorf(failure, "SLA alert sink %s replay failed for environment %s",
                            safeSinkId(sink), scope.environmentId());
                }
            }
        }
        return accepted;
    }

    /** Read-only provider delivery summaries, restricted to the caller's environment scope. */
    public List<AlertSinkStatus> deliveryStatuses(UUID organizationId, Set<UUID> environments) {
        if (environments == null || environments.isEmpty() || sinks.isUnsatisfied()) return List.of();
        List<AlertSinkStatus> result = new ArrayList<>();
        for (ReconciliationSlaAlertSink sink : sinks) {
            try {
                var status = sink.status(organizationId, Set.copyOf(environments));
                String failureCode = status.lastFailureCode();
                if (failureCode != null && !failureCode.matches("[A-Z0-9_.-]{1,80}"))
                    failureCode = "PROVIDER_FAILURE";
                String health = "HEALTHY".equals(status.health()) || "DEGRADED".equals(status.health())
                        || "UNAVAILABLE".equals(status.health()) ? status.health() : "UNAVAILABLE";
                result.add(new AlertSinkStatus(safeSinkId(sink), status.accepted(), status.pending(),
                        status.delivered(), status.failed(), status.oldestPendingAt(),
                        status.lastDeliveredAt(), status.lastFailureAt(), failureCode, health));
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "SLA alert sink %s status query failed", safeSinkId(sink));
                result.add(new AlertSinkStatus(safeSinkId(sink), 0, 0, 0, 0,
                        null, null, null, "STATUS_UNAVAILABLE", "UNAVAILABLE"));
            }
        }
        return List.copyOf(result);
    }

    private static String safeSinkId(ReconciliationSlaAlertSink sink) {
        try {
            String id = sink.sinkId();
            return id != null && id.matches("[a-zA-Z0-9._-]{1,80}") ? id : "invalid-sink-id";
        } catch (RuntimeException failure) {
            return "invalid-sink-id";
        }
    }
}
