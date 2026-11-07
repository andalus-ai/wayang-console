package ai.wayang.console.api;

import ai.wayang.console.application.DeploymentLifecycleService;
import ai.wayang.console.security.ConsoleOperatorContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/v1/console/deployments")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DeploymentResource {
    @Inject ConsoleOperatorContext operator;
    @Inject DeploymentLifecycleService deployments;

    public record CreateRequest(UUID runtimeId, String deploymentId, String workflowId,
            String workflowVersion, String artifactDigest, String manifestDigest) {}

    @POST
    public DeploymentLifecycleService.DeploymentView create(CreateRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        return deployments.create(operator.organizationId(), operator.environmentIds(), request.runtimeId(),
                request.deploymentId(), request.workflowId(), request.workflowVersion(), request.artifactDigest(),
                request.manifestDigest(), operator.subject());
    }

    @GET
    public DeploymentLifecycleService.DeploymentPage list(@DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("25") @QueryParam("size") int size) {
        operator.requireRead();
        return deployments.list(operator.organizationId(), operator.environmentIds(), page, size);
    }

    public record TransitionRequest(String status, String failureReason) {}

    @POST
    @Path("/{deploymentId}/transition")
    public DeploymentLifecycleService.DeploymentView transition(@PathParam("deploymentId") UUID deploymentId,
            TransitionRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        return deployments.transition(operator.organizationId(), operator.environmentIds(), deploymentId,
                request.status(), request.failureReason(), operator.subject());
    }
}
