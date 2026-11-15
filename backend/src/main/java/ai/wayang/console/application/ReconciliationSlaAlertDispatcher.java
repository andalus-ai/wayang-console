package ai.wayang.console.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Dispatches alert signals only after the originating transaction commits. */
@ApplicationScoped
public class ReconciliationSlaAlertDispatcher {
    private static final Logger LOG = Logger.getLogger(ReconciliationSlaAlertDispatcher.class);

    @Inject Instance<ReconciliationSlaAlertSink> sinks;

    void afterCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS)
            DeploymentReconciliationService.SlaAlertTransition transition) {
        for (ReconciliationSlaAlertSink sink : sinks) {
            try {
                sink.publish(transition);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "SLA alert sink %s failed for deployment %s",
                        sink.getClass().getName(), transition.deploymentId());
            }
        }
    }
}
