package ai.wayang.console.api;
import ai.wayang.console.application.PluginApprovalService; import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated; import jakarta.inject.Inject; import jakarta.ws.rs.*; import jakarta.ws.rs.core.MediaType;
@Path("/api/v1/console/plugin-catalog/{pluginId}/versions/{version}/approval") @Authenticated @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
public class PluginApprovalResource {
 @Inject PluginApprovalService approvals; @Inject ConsoleOperatorContext operator;
 public record DecisionRequest(String action,String reason) {}
 @POST @Path("/request") public PluginApprovalService.ApprovalView request(@QueryParam("environmentId") String env,@PathParam("pluginId") String plugin,@PathParam("version") String version){
 operator.requirePluginCatalogAdmin();return approvals.request(operator.organizationId(),operator.requireEnvironment(env),plugin,version,operator.subject());}
 @GET public PluginApprovalService.ApprovalView get(@QueryParam("environmentId") String env,@PathParam("pluginId") String plugin,@PathParam("version") String version){
 operator.requirePluginApprovalRead();return approvals.get(operator.organizationId(),operator.requireEnvironment(env),plugin,version);}
 @POST @Path("/decision") public PluginApprovalService.ApprovalView decide(@QueryParam("environmentId") String env,@PathParam("pluginId") String plugin,@PathParam("version") String version,DecisionRequest request){
 operator.requirePluginApprovalAdmin();return approvals.decide(operator.organizationId(),operator.requireEnvironment(env),plugin,version,operator.subject(),request==null?null:new PluginApprovalService.Decision(request.action(),request.reason()));}
}