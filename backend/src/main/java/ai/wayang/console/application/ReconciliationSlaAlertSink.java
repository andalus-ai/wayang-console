package ai.wayang.console.application;

/**
 * Extension point for consuming committed reconciliation SLA transitions.
 * Implementations must not perform blocking work in the observer callback.
 * Durable delivery and provider-specific retries belong to the extension.
 */
public interface ReconciliationSlaAlertSink {
    void publish(DeploymentReconciliationService.SlaAlertTransition transition);
}
