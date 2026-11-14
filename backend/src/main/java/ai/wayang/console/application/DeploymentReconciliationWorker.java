package ai.wayang.console.application;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Durable reconciliation recovery loop. All decisions are persisted before a command
 * is queued; each deployment is locked again by the command service before dispatch.
 */
@ApplicationScoped
public class DeploymentReconciliationWorker {
    @Inject DeploymentReconciliationService reconciliation;
    @Inject RuntimeCommandService commands;

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void recoverTimedOutDeployments() {
        Instant now = Instant.now();
        reconciliation.reconcileExpiredObservations(now);
        reconciliation.scheduleTimedOutRetries(now);
        for (var deploymentId : reconciliation.dueRetryDeploymentIds(now)) {
            commands.dispatchDueRetry(deploymentId, now);
        }
    }
}
