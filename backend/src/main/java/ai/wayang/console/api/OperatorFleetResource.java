package ai.wayang.console.api;

import ai.wayang.console.application.OperatorFleetService;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.util.UUID;

@Path("/api/v1/console")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OperatorFleetResource {
    @Inject ConsoleOperatorContext operator;
    @Inject OperatorFleetService fleet;

    public record IssueTokenRequest(UUID environmentId, Long ttlSeconds) {}
    public record IssueTokenResponse(String token, java.time.Instant expiresAt) {}
    public record ErrorResponse(String code, String message) {}

    @POST
    @Path("/environments/enrollment-tokens")
    public IssueTokenResponse issueToken(IssueTokenRequest request) {
        operator.requireAdmin();
        if (request == null) throw new BadRequestException("Request body is required");
        long seconds = request.ttlSeconds() == null ? 3600 : request.ttlSeconds();
        var result = fleet.issueToken(operator.organizationId(), request.environmentId(),
                operator.environmentIds(), Duration.ofSeconds(seconds));
        return new IssueTokenResponse(result.token(), result.expiresAt());
    }

    @DELETE
    @Path("/enrollment-tokens/{tokenId}")
    public void revokeToken(@PathParam("tokenId") UUID tokenId) {
        operator.requireAdmin();
        fleet.revokeEnrollmentToken(operator.organizationId(), tokenId, operator.environmentIds(), subject());
    }

    @GET
    @Path("/runtimes")
    public OperatorFleetService.PageResult list(@QueryParam("status") String status,
            @DefaultValue("0") @QueryParam("page") int page, @DefaultValue("25") @QueryParam("size") int size) {
        operator.requireRead();
        return fleet.list(operator.organizationId(), operator.environmentIds(), status, page, size);
    }

    @GET
    @Path("/runtimes/{runtimeId}")
    public OperatorFleetService.RuntimeDetail get(@PathParam("runtimeId") UUID runtimeId) {
        operator.requireRead();
        return fleet.get(operator.organizationId(), operator.environmentIds(), runtimeId);
    }

    @POST
    @Path("/runtimes/{runtimeId}/revoke")
    public void revokeRuntime(@PathParam("runtimeId") UUID runtimeId) {
        operator.requireAdmin();
        fleet.revokeRuntime(operator.organizationId(), operator.environmentIds(), runtimeId, subject());
    }

    private String subject() {
        return operatorSubject();
    }

    private String operatorSubject() {
        // Subject is obtained from the authenticated JWT, never from request data.
        return operator.subject();
    }
}
