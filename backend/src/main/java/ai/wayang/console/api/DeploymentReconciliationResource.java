package ai.wayang.console.api;

import ai.wayang.console.application.DeploymentReconciliationService;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/v1/console/deployments")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DeploymentReconciliationResource {
    @Inject ConsoleOperatorContext operator;
    @Inject DeploymentReconciliationService reconciliation;

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

    @GET
    @Path("/{deploymentId}/reconciliation")
    public DeploymentReconciliationService.ReconciliationView get(@PathParam("deploymentId") UUID deploymentId) {
        operator.requireRead();
        return reconciliation.get(operator.organizationId(), operator.environmentIds(), deploymentId);
    }
}
