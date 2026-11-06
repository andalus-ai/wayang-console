package ai.wayang.console.api;

import ai.wayang.console.application.RuntimeRegistryService;
import ai.wayang.console.application.RuntimeInventoryService;
import java.util.List;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;

@Path("/api/v1/runtime")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuntimeRegistryResource {
    @Inject RuntimeRegistryService registry;
    @Inject RuntimeInventoryService inventory;

    public record EnrollRequest(String enrollmentToken, String instanceId, String runtimeVersion,
                                String deploymentId, String hostname, String platform) {}
    public record EnrollResponse(String registrationId, String registrationToken,
                                 Instant registrationExpiresAt, Instant leaseExpiresAt,
                                 int heartbeatIntervalSeconds) {}
    public record InventoryRequest(List<RuntimeInventoryService.CapabilityInput> capabilities,
                                   List<RuntimeInventoryService.WorkflowInput> workflows) {}
    public record InventoryResponse(String revision, int capabilityCount, int workflowCount) {}
    public record HeartbeatRequest(String health) {}
    public record HeartbeatResponse(Instant leaseExpiresAt, int heartbeatIntervalSeconds) {}

    @POST
    @Path("/enroll")
    public Response enroll(EnrollRequest request) {
        if (request == null) return Response.status(400).build();
        try {
            var result = registry.enroll(request.enrollmentToken(), request.instanceId(), request.runtimeVersion(),
                    request.deploymentId(), request.hostname(), request.platform());
            return Response.ok(new EnrollResponse(result.registrationId(), result.registrationToken(),
                    result.registrationExpiresAt(), result.leaseExpiresAt(), result.heartbeatIntervalSeconds())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(401).entity(new ErrorResponse("invalid_enrollment", e.getMessage())).build();
        }
    }

    @POST
    @Path("/heartbeat")
    public Response heartbeat(@HeaderParam("Authorization") String authorization, HeartbeatRequest request) {
        String token = bearer(authorization);
        if (token == null) return Response.status(401).entity(new ErrorResponse("unauthorized", "Bearer token required")).build();
        try {
            var result = registry.heartbeat(token, request == null ? null : request.health());
            return Response.ok(new HeartbeatResponse(result.leaseExpiresAt(), result.heartbeatIntervalSeconds())).build();
        } catch (SecurityException e) {
            return Response.status(401).entity(new ErrorResponse("unauthorized", "Runtime credential is invalid or expired")).build();
        }
    }

    @POST
    @Path("/inventory")
    public Response reportInventory(@HeaderParam("Authorization") String authorization, InventoryRequest request) {
        String token = bearer(authorization);
        if (token == null) return Response.status(401).entity(new ErrorResponse("unauthorized", "Bearer token required")).build();
        try {
            var runtime = registry.authenticate(token);
            var result = inventory.replaceInventory(runtime.id(),
                    request == null ? null : request.capabilities(),
                    request == null ? null : request.workflows());
            return Response.ok(new InventoryResponse(result.revision(), result.capabilityCount(), result.workflowCount())).build();
        } catch (SecurityException e) {
            return Response.status(401).entity(new ErrorResponse("unauthorized", "Runtime credential is invalid or expired")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(new ErrorResponse("invalid_inventory", e.getMessage())).build();
        }
    }

    private static String bearer(String value) {
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public record ErrorResponse(String code, String message) {}
}
