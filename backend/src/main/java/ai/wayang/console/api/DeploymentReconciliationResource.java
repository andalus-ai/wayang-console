package ai.wayang.console.api;

import ai.wayang.console.application.DeploymentReconciliationService;
import ai.wayang.console.application.RuntimeCommandService;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

@Path("/api/v1/console/deployments")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DeploymentReconciliationResource {
    @Inject ConsoleOperatorContext operator;
    @Inject DeploymentReconciliationService reconciliation;
    @Inject RuntimeCommandService commands;

    public record DesiredStateRequest(String desiredState) {}

    @PUT
    @Path("/{deploymentId}/desired-state")
    public DeploymentReconciliationService.ReconciliationView setDesired(
            @PathParam("deploymentId") UUID deploymentId, DesiredStateRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        return reconciliation.setDesired(operator.organizationId(), operator.environmentIds(),
                deploymentId, request.desiredState(), operator.subject());
    }

    public record OperatorRetryRequest(String reason) {}

    @POST
    @Path("/{deploymentId}/reconciliation/retry")
    public DeploymentReconciliationService.ReconciliationView retry(
            @PathParam("deploymentId") UUID deploymentId, OperatorRetryRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        return reconciliation.requestOperatorRetry(operator.organizationId(), operator.environmentIds(),
                deploymentId, operator.subject(), request.reason());
    }

    @GET
    @Path("/reconciliation/summary")
    public DeploymentReconciliationService.ReconciliationFleetSummary reconciliationSummary() {
        operator.requireRead();
        return reconciliation.fleetSummary(operator.organizationId(), operator.environmentIds(),
                java.time.Instant.now());
    }

    @GET
    @Path("/reconciliation/health")
    public DeploymentReconciliationService.ReconciliationHealth reconciliationHealth() {
        operator.requireRead();
        return reconciliation.reconciliationHealth(operator.organizationId(), operator.environmentIds(),
                java.time.Instant.now());
    }

    @GET
    @Path("/reconciliation/sla/metrics")
    public DeploymentReconciliationService.ReconciliationSlaMetrics reconciliationSlaMetrics() {
        operator.requireRead();
        return reconciliation.reconciliationSlaMetrics(operator.organizationId(), operator.environmentIds(),
                java.time.Instant.now());
    }

    /**
     * Prometheus text exposition for the authenticated operator's scope.
     * No tenant, environment, deployment, runtime, or user identifiers are emitted as labels.
     */
    @GET
    @Path("/reconciliation/sla/metrics/prometheus")
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    public String reconciliationSlaPrometheusMetrics() {
        operator.requireRead();
        var metrics = reconciliation.reconciliationSlaMetrics(operator.organizationId(),
                operator.environmentIds(), java.time.Instant.now());
        StringBuilder out = new StringBuilder(2048);
        appendGauge(out, "wayang_console_reconciliation_unresolved",
                "Unresolved deployment reconciliations in the authenticated scope.", metrics.unresolved());
        appendGauge(out, "wayang_console_reconciliation_fresh",
                "Unresolved reconciliations below the warning SLA threshold.", metrics.fresh());
        appendGauge(out, "wayang_console_reconciliation_warning",
                "Unresolved reconciliations in the warning SLA band.", metrics.warning());
        appendGauge(out, "wayang_console_reconciliation_critical",
                "Unresolved reconciliations in the critical SLA band.", metrics.critical());
        appendGauge(out, "wayang_console_reconciliation_sla_warning_threshold_seconds",
                "Configured warning threshold in seconds.", metrics.warningThresholdSeconds());
        appendGauge(out, "wayang_console_reconciliation_sla_critical_threshold_seconds",
                "Configured critical threshold in seconds.", metrics.criticalThresholdSeconds());
        out.append("# HELP wayang_console_reconciliation_sla_transitions_total Append-only SLA transition events by destination state.\\n")
                .append("# TYPE wayang_console_reconciliation_sla_transitions_total counter\\n")
                .append("wayang_console_reconciliation_sla_transitions_total{to_status=\"HEALTHY\"} ")
                .append(metrics.transitionsToHealthy()).append('\\n')
                .append("wayang_console_reconciliation_sla_transitions_total{to_status=\"WARNING\"} ")
                .append(metrics.transitionsToWarning()).append('\\n')
                .append("wayang_console_reconciliation_sla_transitions_total{to_status=\"CRITICAL\"} ")
                .append(metrics.transitionsToCritical()).append('\\n');
        out.append("# HELP wayang_console_reconciliation_sla_policy_info Active SLA policy version.\\n")
                .append("# TYPE wayang_console_reconciliation_sla_policy_info gauge\\n")
                .append("wayang_console_reconciliation_sla_policy_info{version=\"")
                .append(metrics.policyVersion()).append("\"} 1\\n");
        return out.toString();
    }

    private static void appendGauge(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\\n')
                .append("# TYPE ").append(name).append(" gauge\\n")
                .append(name).append(' ').append(value).append('\\n');
    }

    @GET
    @Path("/reconciliation/sla/history")
    public DeploymentReconciliationService.SlaTransitionPage reconciliationSlaHistory(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size) {
        operator.requireRead();
        return reconciliation.slaHistory(operator.organizationId(), operator.environmentIds(), page, size);
    }

    @GET
    @Path("/reconciliation")
    public DeploymentReconciliationService.ReconciliationQueuePage queue(
            @QueryParam("state") String state,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size) {
        operator.requireRead();
        return reconciliation.listQueue(operator.organizationId(), operator.environmentIds(), state, page, size);
    }

    @GET
    @Path("/{deploymentId}/reconciliation/audit")
    public DeploymentReconciliationService.AuditTimelinePage auditTimeline(
            @PathParam("deploymentId") UUID deploymentId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size) {
        operator.requireRead();
        return reconciliation.auditTimeline(operator.organizationId(), operator.environmentIds(),
                deploymentId, page, size);
    }

    public record SupersessionResolutionRequest(String reason) {}

    @POST
    @Path("/{deploymentId}/commands/{commandId}/resolve-supersession")
    public RuntimeCommandService.DeploymentCommandView resolveSupersession(
            @PathParam("deploymentId") UUID deploymentId,
            @PathParam("commandId") UUID commandId,
            SupersessionResolutionRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        return commands.resolveUnconfirmedSupersession(operator.organizationId(), operator.environmentIds(),
                deploymentId, commandId, operator.subject(), request.reason());
    }

    @GET
    @Path("/{deploymentId}/commands")
    public RuntimeCommandService.DeploymentCommandPage commandHistory(
            @PathParam("deploymentId") UUID deploymentId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("25") int size) {
        operator.requireRead();
        return commands.listDeploymentCommands(operator.organizationId(), operator.environmentIds(),
                deploymentId, page, size);
    }

    @GET
    @Path("/{deploymentId}/reconciliation")
    public DeploymentReconciliationService.ReconciliationView get(@PathParam("deploymentId") UUID deploymentId) {
        operator.requireRead();
        return reconciliation.get(operator.organizationId(), operator.environmentIds(), deploymentId);
    }
}
