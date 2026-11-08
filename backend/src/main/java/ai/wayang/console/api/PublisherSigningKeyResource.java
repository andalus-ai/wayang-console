package ai.wayang.console.api;

import ai.wayang.console.application.PublisherSigningKeyTrustService;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/v1/console/publisher-signing-keys")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PublisherSigningKeyResource {
    @Inject PublisherSigningKeyTrustService trust;
    @Inject ConsoleOperatorContext operator;

    public record RegisterRequest(String keyId, String publicKeyDerBase64) {}
    public record RevokeRequest(String reason) {}

    @GET
    public List<PublisherSigningKeyTrustService.KeyView> list(@QueryParam("environmentId") String environmentId) {
        operator.requirePublisherTrustRead();
        return trust.list(operator.organizationId(), operator.requireEnvironment(environmentId));
    }

    @POST
    public PublisherSigningKeyTrustService.KeyView register(@QueryParam("environmentId") String environmentId,
            RegisterRequest request) {
        operator.requirePublisherTrustAdmin();
        return trust.register(operator.organizationId(), operator.requireEnvironment(environmentId),
                operator.subject(), request == null ? null :
                        new PublisherSigningKeyTrustService.RegisterCommand(request.keyId(), request.publicKeyDerBase64()));
    }

    @GET
    @Path("/{keyId}/history")
    public List<PublisherSigningKeyTrustService.KeyEvent> history(
            @QueryParam("environmentId") String environmentId, @PathParam("keyId") String keyId) {
        operator.requirePublisherTrustRead();
        return trust.history(operator.organizationId(), operator.requireEnvironment(environmentId), keyId);
    }

    @POST
    @Path("/{keyId}/revoke")
    public PublisherSigningKeyTrustService.KeyView revoke(@QueryParam("environmentId") String environmentId,
            @PathParam("keyId") String keyId, RevokeRequest request) {
        operator.requirePublisherTrustAdmin();
        return trust.revoke(operator.organizationId(), operator.requireEnvironment(environmentId),
                keyId, operator.subject(), request == null ? null : new PublisherSigningKeyTrustService.RevokeCommand(request.reason()));
    }
}
