package ai.wayang.console.api;

import ai.wayang.console.application.PluginCatalogService;
import ai.wayang.console.security.ConsoleOperatorContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/v1/console/plugin-catalog")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PluginCatalogResource {
    @Inject PluginCatalogService catalog;
    @Inject ConsoleOperatorContext operator;

    public record PublishRequest(String pluginId, String pluginVersion, String manifestDigest, JsonNode metadata) {}

    @GET
    public List<PluginCatalogService.CatalogEntry> list(@QueryParam("environmentId") String environmentId) {
        operator.requirePluginCatalogRead();
        var environment = operator.requireEnvironment(environmentId);
        return catalog.list(operator.organizationId(), environment);
    }

    @GET
    @Path("/{pluginId}/versions/{version}")
    public PluginCatalogService.CatalogEntry get(@QueryParam("environmentId") String environmentId,
            @PathParam("pluginId") String pluginId, @PathParam("version") String version) {
        operator.requirePluginCatalogRead();
        var environment = operator.requireEnvironment(environmentId);
        return catalog.get(operator.organizationId(), environment, pluginId, version);
    }

    @PUT
    @Path("/{pluginId}/versions/{version}")
    public PluginCatalogService.CatalogEntry publish(@QueryParam("environmentId") String environmentId,
            @PathParam("pluginId") String pluginId, @PathParam("version") String version, PublishRequest request) {
        operator.requirePluginCatalogAdmin();
        var environment = operator.requireEnvironment(environmentId);
        if (request == null) throw new BadRequestException("Request body is required");
        if (!pluginId.equals(request.pluginId()) || !version.equals(request.pluginVersion()))
            throw new BadRequestException("Path plugin identity must match request body");
        return catalog.publish(operator.organizationId(), environment, operator.subject(),
                new PluginCatalogService.PublishCommand(request.pluginId(), request.pluginVersion(),
                        request.manifestDigest(), request.metadata()));
    }
}
