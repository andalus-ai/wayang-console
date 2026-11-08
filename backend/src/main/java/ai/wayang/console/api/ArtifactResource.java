package ai.wayang.console.api;
import ai.wayang.console.application.*;
import ai.wayang.console.security.ConsoleOperatorContext;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.io.InputStream;
@Path("/api/v1")
public class ArtifactResource {
 @Inject ArtifactRegistryService artifacts;@Inject RuntimeRegistryService runtimes;@Inject ConsoleOperatorContext operator;
 @POST @Path("/console/artifacts") @Authenticated @Consumes(MediaType.APPLICATION_OCTET_STREAM) @Produces(MediaType.APPLICATION_JSON)
 public ArtifactRegistryService.ArtifactView upload(@HeaderParam("X-Artifact-Digest")String digest,
  @HeaderParam("X-Manifest-Digest")String manifest,@HeaderParam("X-Signing-Key-Id")String keyId,
  @HeaderParam("X-Artifact-Signature")String signature,InputStream body){
  operator.requireAdmin();if(body==null)throw new BadRequestException("Artifact body required");
  var scopes=operator.environmentIds();if(scopes==null||scopes.size()!=1)throw new BadRequestException("Upload requires exactly one selected environment");
  return artifacts.register(operator.organizationId(),scopes.iterator().next(),operator.subject(),digest,manifest,keyId,signature,body);
 }
 @GET @Path("/runtime/artifacts/{digest}") @Produces(MediaType.APPLICATION_OCTET_STREAM)
 public Response download(@HeaderParam("Authorization")String authorization,@PathParam("digest")String digest){
  String token=bearer(authorization);if(token==null)return unauthorized();
  try{var runtime=runtimes.authenticate(token);var a=artifacts.openForRuntime(runtime,digest);
   StreamingOutput body=out->{try(InputStream in=a.content()){in.transferTo(out);}};
   return Response.ok(body).header("Content-Length",a.sizeBytes()).header("X-Artifact-Digest",a.digest()).build();
  }catch(SecurityException e){return unauthorized();}
 }
 private static Response unauthorized(){return Response.status(401).entity("Runtime credential invalid or expired").build();}
 private static String bearer(String h){if(h==null||!h.regionMatches(true,0,"Bearer ",0,7))return null;String t=h.substring(7).trim();return t.isEmpty()?null:t;}
}