package ai.wayang.console.api;

import ai.wayang.console.application.DeploymentReconciliationService;
import ai.wayang.console.application.RuntimeRegistryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;

@Path("/api/v1/runtime/deployments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuntimeDeploymentObservationResource {
    @Inject RuntimeRegistryService registry;
    @Inject DeploymentReconciliationService reconciliation;

    public record ObservationRequest(String state, String artifactDigest, UUID commandId,
            long generation, long sequence, Instant observedAt, String diagnostic) {}

    @POST
    @Path("/{deploymentId}/observed-state")
    public Response report(@HeaderParam("Authorization") String authorization,
            @PathParam("deploymentId") UUID deploymentId, ObservationRequest request) {
        String token = bearer(authorization);
        if (token == null) return Response.status(401).entity(new ErrorResponse("unauthorized", "Bearer token required")).build();
        if (request == null) return Response.status(400).entity(new ErrorResponse("invalid_observation", "Request body is required")).build();
        try {
            var runtime = registry.authenticate(token);
            var result = reconciliation.report(runtime, deploymentId, new DeploymentReconciliationService.Observation(
                    request.state(), request.artifactDigest(), request.commandId(), request.generation(), request.sequence(),
                    request.observedAt(), request.diagnostic()));
            return Response.ok(result).build();
        } catch (SecurityException e) {
            return Response.status(401).entity(new ErrorResponse("unauthorized", "Runtime credential is invalid or expired")).build();
        } catch (NotFoundException e) {
            return Response.status(404).entity(new ErrorResponse("deployment_not_found", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(new ErrorResponse("invalid_observation", e.getMessage())).build();
        } catch (WebApplicationException e) {
            return Response.status(e.getResponse().getStatus()).entity(new ErrorResponse("observation_rejected", e.getMessage())).build();
        }
    }
    private static String bearer(String value) {
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
    public record ErrorResponse(String code, String message) {}
}
