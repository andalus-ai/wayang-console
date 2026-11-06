package ai.wayang.console.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class SystemResource {
    @GET
    @Path("/system")
    public Map<String, Object> system() {
        return Map.of("product", "Wayang Console", "apiVersion", "v1",
            "role", "management-control-plane", "workflowExecution", false,
            "registrationEnabled", false);
    }
}
