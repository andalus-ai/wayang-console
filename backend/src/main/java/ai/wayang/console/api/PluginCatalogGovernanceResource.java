package ai.wayang.console.api;

import ai.wayang.console.application.PluginCatalogGovernanceService;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/v1/console/plugin-catalog/{pluginId}/versions/{version}/governance")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PluginCatalogGovernanceResource {
    @Inject PluginCatalogGovernanceService governance;
    @Inject ConsoleOperatorContext operator;

    public record TransitionRequest(PluginCatalogGovernanceService.Action action, String reason) {}

    @GET
    public PluginCatalogGovernanceService.GovernanceStatus get(@QueryParam("environmentId") String environmentId,
            @PathParam("pluginId") String pluginId, @PathParam("version") String version) {
        operator.requirePluginCatalogRead();
        var environment = operator.requireEnvironment(environmentId);
        return governance.get(operator.organizationId(), environment, pluginId, version);
    }

    @GET
    @Path("/history")
    public List<PluginCatalogGovernanceService.GovernanceEvent> history(
            @QueryParam("environmentId") String environmentId,
            @PathParam("pluginId") String pluginId, @PathParam("version") String version) {
        operator.requirePluginCatalogRead();
        var environment = operator.requireEnvironment(environmentId);
        return governance.history(operator.organizationId(), environment, pluginId, version);
    }

    @POST
    public PluginCatalogGovernanceService.GovernanceStatus transition(
            @QueryParam("environmentId") String environmentId,
            @PathParam("pluginId") String pluginId, @PathParam("version") String version,
            TransitionRequest request) {
        operator.requirePluginCatalogAdmin();
        var environment = operator.requireEnvironment(environmentId);
        if (request == null) throw new BadRequestException("Request body is required");
        return governance.transition(operator.organizationId(), environment, pluginId, version,
                operator.subject(), new PluginCatalogGovernanceService.TransitionCommand(request.action(), request.reason()));
    }
}
