package ai.wayang.console.api;

import ai.wayang.console.application.RuntimeCommandService;
import ai.wayang.console.application.RuntimeRegistryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/v1/runtime/commands")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RuntimeCommandResource {
    @Inject RuntimeRegistryService registry;
    @Inject RuntimeCommandService commands;

    public record PollRequest(Integer limit) {}
    public record AckRequest(String status, String message, String verifiedArtifactDigest) {}
    public record ErrorResponse(String code, String message) {}

    @POST
    @Path("/poll")
    public Response poll(@HeaderParam("Authorization") String authorization, PollRequest request) {
        String token = bearer(authorization);
        if (token == null) return unauthorized();
        try {
            var runtime = registry.authenticate(token);
            int limit = request == null || request.limit() == null ? 10 : request.limit();
            return Response.ok(commands.poll(runtime, limit)).build();
        } catch (SecurityException e) {
            return unauthorized();
        }
    }

    @POST
    @Path("/{commandId}/ack")
    public Response acknowledge(@HeaderParam("Authorization") String authorization,
            @PathParam("commandId") UUID commandId, AckRequest request) {
        String token = bearer(authorization);
        if (token == null) return unauthorized();
        if (request == null) return Response.status(400).entity(new ErrorResponse("invalid_request", "Request body is required")).build();
        try {
            var runtime = registry.authenticate(token);
            return Response.ok(commands.acknowledge(runtime, commandId, request.status(), request.message(), request.verifiedArtifactDigest())).build();
        } catch (SecurityException e) {
            return unauthorized();
        } catch (NotFoundException e) {
            return Response.status(404).entity(new ErrorResponse("not_found", "Command not found")).build();
        }
    }

    private static Response unauthorized() {
        return Response.status(401).entity(new ErrorResponse("unauthorized", "Runtime credential is invalid or expired")).build();
    }
    private static String bearer(String value) {
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
