package ai.wayang.console.application;
import ai.wayang.console.adapter.persistence.ArtifactEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
@ApplicationScoped
public class ArtifactRegistryService {
 @Inject ArtifactStorage storage;
 @Inject AuditIntegrityService audit;
 @Inject @ConfigProperty(name="wayang.console.artifacts.max-size-bytes",defaultValue="268435456") long maxBytes;
 @Inject @ConfigProperty(name="wayang.console.artifacts.ed25519-public-key",defaultValue="") String trustedPublicKey;
 public record ArtifactView(UUID id,String artifactDigest,String manifestDigest,long sizeBytes,String signingKeyId,Instant createdAt){}
 public record Download(String digest,String manifestDigest,String signature,String signingKeyId,long sizeBytes,InputStream content){}
 @Transactional
 public ArtifactView register(UUID org,UUID env,String actor,String digest,String manifest,String keyId,String signature,InputStream content){
  validateDigest(digest,"artifactDigest");validateDigest(manifest,"manifestDigest");required(keyId,"keyId");required(signature,"signature");
  if(maxBytes<1)throw new IllegalStateException("Artifact size limit must be positive");
  ArtifactEntity old=ArtifactEntity.find("organizationId = ?1 and environmentId = ?2 and artifactDigest = ?3",org,env,digest.toLowerCase()).firstResult();
  if(old!=null){if(!old.manifestDigest.equalsIgnoreCase(manifest))throw new WebApplicationException("Artifact already registered with another manifest",409);return view(old);}
  ArtifactStorage.StoredObject stored;
  try{stored=storage.put(org,env,digest,content,maxBytes);}catch(IOException e){throw new WebApplicationException("Artifact upload failed: "+e.getMessage(),400);}
  try{
   verify(stored.sha256(),manifest.toLowerCase(),keyId,signature);
   Instant now=Instant.now();ArtifactEntity a=new ArtifactEntity();a.id=UUID.randomUUID();a.organizationId=org;a.environmentId=env;
   a.artifactDigest=stored.sha256();a.manifestDigest=manifest.toLowerCase();a.objectKey=stored.objectKey();a.sizeBytes=stored.sizeBytes();
   a.signature=signature;a.signingKeyId=keyId;a.uploadedBy=actor;a.createdAt=now;a.persist();
   audit.append(org,env,actor,"ARTIFACT_REGISTERED","ARTIFACT",a.id.toString(),now,"SUCCESS");return view(a);
  }catch(RuntimeException e){try{storage.delete(stored.objectKey());}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
 }
 @Transactional public void assertRegistered(UUID org,UUID env,String digest,String manifest){
  ArtifactEntity a=ArtifactEntity.find("organizationId = ?1 and environmentId = ?2 and artifactDigest = ?3",org,env,digest.toLowerCase()).firstResult();
  if(a==null||!a.manifestDigest.equalsIgnoreCase(manifest))throw new WebApplicationException("Verified artifact and manifest are required",409);
 }
 @Transactional public Download openForRuntime(RuntimeRegistryService.AuthenticatedRuntime runtime,String digest){
  validateDigest(digest,"artifactDigest");
  ArtifactEntity a=ArtifactEntity.find("organizationId = ?1 and environmentId = ?2 and artifactDigest = ?3",runtime.organizationId(),runtime.environmentId(),digest.toLowerCase()).firstResult();
  if(a==null)throw new NotFoundException("Artifact not found");
  try{return new Download(a.artifactDigest,a.manifestDigest,a.signature,a.signingKeyId,a.sizeBytes,storage.open(a.objectKey));}catch(IOException e){throw new WebApplicationException("Artifact storage unavailable",503);}
 }
 private void verify(String digest,String manifest,String keyId,String sig){
  try{
   if(!ArtifactSignatureVerifier.verify(trustedPublicKey,digest,manifest,keyId,sig))
    throw new WebApplicationException("Artifact signature verification failed",400);
  }catch(WebApplicationException e){throw e;}
  catch(IllegalStateException e){throw new WebApplicationException(e.getMessage(),503);}
  catch(Exception e){throw new WebApplicationException("Invalid signature or trust key",400);}
 }
 private static void validateDigest(String d,String n){if(d==null||!d.matches("(?i)sha256:[0-9a-f]{64}"))throw new WebApplicationException(n+" must be sha256:<64 hex>",400);}
 private static String required(String s,String n){if(s==null||s.isBlank())throw new WebApplicationException(n+" required",400);return s.trim();}
 private static ArtifactView view(ArtifactEntity a){return new ArtifactView(a.id,a.artifactDigest,a.manifestDigest,a.sizeBytes,a.signingKeyId,a.createdAt);}
}