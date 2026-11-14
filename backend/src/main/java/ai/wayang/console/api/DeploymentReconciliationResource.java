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
